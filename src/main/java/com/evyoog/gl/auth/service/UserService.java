package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.Role;
import com.evyoog.gl.auth.domain.User;
import com.evyoog.gl.auth.domain.UserRole;
import com.evyoog.gl.auth.dto.AssignRoleRequest;
import com.evyoog.gl.auth.dto.CreateUserRequest;
import com.evyoog.gl.auth.dto.ResetPasswordResponse;
import com.evyoog.gl.auth.dto.UserResponse;
import com.evyoog.gl.auth.dto.UserRoleAssignmentResponse;
import com.evyoog.gl.auth.repository.RoleRepository;
import com.evyoog.gl.auth.repository.UserRepository;
import com.evyoog.gl.auth.repository.UserRoleRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public UserResponse create(CreateUserRequest request, String performedBy) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("DUPLICATE_EMAIL",
                    "A user with email '" + request.email() + "' already exists.", "email");
        }

        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role", request.roleId()));
        LegalEntity legalEntity = legalEntityRepository.findById(request.legalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", request.legalEntityId()));

        User user = User.builder()
                .email(request.email())
                .fullName(request.fullName())
                .passwordHash(passwordEncoder.encode(request.password()))
                .mustChangePwd(true)
                .createdBy(performedBy)
                .build();
        User saved = userRepository.save(user);

        UserRole userRole = UserRole.builder()
                .user(saved)
                .role(role)
                .legalEntity(legalEntity)
                .assignedBy(performedBy)
                .build();
        userRoleRepository.save(userRole);

        UserResponse response = toResponse(saved);
        auditService.log(AuditAction.CREATE, "auth_user", saved.getId(), null, response, performedBy);
        return response;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list(UUID legalEntityId) {
        if (legalEntityId == null) {
            return userRepository.findAll().stream().map(this::toResponse).toList();
        }
        return userRoleRepository.findByLegalEntityId(legalEntityId).stream()
                .map(UserRole::getUser)
                .distinct()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public UserResponse deactivate(UUID id, String performedBy) {
        User user = findOrThrow(id);
        UserResponse before = toResponse(user);

        user.setActive(false);
        User saved = userRepository.save(user);

        UserResponse response = toResponse(saved);
        auditService.log(AuditAction.UPDATE, "auth_user", saved.getId(), before, response, performedBy);
        return response;
    }

    @Transactional
    public ResetPasswordResponse resetPassword(UUID id, String performedBy) {
        User user = findOrThrow(id);

        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePwd(true);
        User saved = userRepository.save(user);

        auditService.log(AuditAction.UPDATE, "auth_user", saved.getId(), null,
                "password reset", performedBy);
        return new ResetPasswordResponse(saved.getId(), temporaryPassword);
    }

    @Transactional
    public UserRoleAssignmentResponse assignRole(UUID userId, AssignRoleRequest request, String performedBy) {
        User user = findOrThrow(userId);
        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role", request.roleId()));
        LegalEntity legalEntity = legalEntityRepository.findById(request.legalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", request.legalEntityId()));

        UserRole userRole = UserRole.builder()
                .user(user)
                .role(role)
                .legalEntity(legalEntity)
                .assignedBy(performedBy)
                .build();
        UserRole saved = userRoleRepository.save(userRole);

        UserRoleAssignmentResponse response = new UserRoleAssignmentResponse(
                saved.getId(), role.getId(), role.getCode(), role.getName(),
                legalEntity.getId(), legalEntity.getCode());
        auditService.log(AuditAction.CREATE, "auth_user_role", saved.getId(), null, response, performedBy);
        return response;
    }

    @Transactional(readOnly = true)
    public List<UserRoleAssignmentResponse> listRoles(UUID userId) {
        findOrThrow(userId);
        return userRoleRepository.findByUserId(userId).stream()
                .map(ur -> new UserRoleAssignmentResponse(
                        ur.getId(), ur.getRole().getId(), ur.getRole().getCode(), ur.getRole().getName(),
                        ur.getLegalEntity().getId(), ur.getLegalEntity().getCode()))
                .toList();
    }

    @Transactional
    public void removeRole(UUID userId, UUID roleId, String performedBy) {
        findOrThrow(userId);
        userRoleRepository.deleteByUserIdAndRoleId(userId, roleId);
        auditService.log(AuditAction.DELETE, "auth_user_role", userId, roleId, null, performedBy);
    }

    private String generateTemporaryPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),
                user.isActive(), user.isMustChangePwd(), user.getLastLoginAt(), user.getCreatedAt());
    }

    private User findOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }
}
