package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.Permission;
import com.evyoog.gl.auth.domain.Role;
import com.evyoog.gl.auth.dto.CreateRoleRequest;
import com.evyoog.gl.auth.dto.PermissionResponse;
import com.evyoog.gl.auth.dto.RoleResponse;
import com.evyoog.gl.auth.dto.UpdateRoleRequest;
import com.evyoog.gl.auth.repository.PermissionRepository;
import com.evyoog.gl.auth.repository.RoleRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AuditService auditService;

    @Transactional
    public RoleResponse create(CreateRoleRequest request, String performedBy) {
        if (roleRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException("DUPLICATE_ROLE_CODE",
                    "A role with code '" + request.code() + "' already exists.", "code");
        }

        Role role = Role.builder()
                .code(request.code())
                .name(request.name())
                .description(request.description())
                .isSystemRole(false)
                .permissions(resolvePermissions(request.permissionCodes()))
                .createdBy(performedBy)
                .build();
        Role saved = roleRepository.save(role);

        RoleResponse response = toResponse(saved);
        auditService.log(AuditAction.CREATE, "auth_role", saved.getId(), null, response, performedBy);
        return response;
    }

    @Transactional
    public RoleResponse update(UUID id, UpdateRoleRequest request, String performedBy) {
        Role role = findOrThrow(id);
        RoleResponse before = toResponse(role);

        if (role.isSystemRole()) {
            throw new EvyoogException("SYSTEM_ROLE_IMMUTABLE",
                    "Seeded system roles cannot be modified.", HttpStatus.CONFLICT);
        }

        if (request.name() != null) {
            role.setName(request.name());
        }
        if (request.description() != null) {
            role.setDescription(request.description());
        }
        if (request.permissionCodes() != null) {
            role.setPermissions(resolvePermissions(request.permissionCodes()));
        }
        if (request.isActive() != null) {
            role.setActive(request.isActive());
        }
        role.setUpdatedBy(performedBy);

        Role saved = roleRepository.save(role);
        RoleResponse response = toResponse(saved);
        auditService.log(AuditAction.UPDATE, "auth_role", saved.getId(), before, response, performedBy);
        return response;
    }

    @Transactional(readOnly = true)
    public RoleResponse getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roleRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return permissionRepository.findAll().stream()
                .map(p -> new PermissionResponse(p.getId(), p.getCode(), p.getModule(), p.getFunction(), p.getAction(), p.getDescription()))
                .toList();
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return new HashSet<>();
        }
        List<Permission> found = permissionRepository.findAll().stream()
                .filter(p -> codes.contains(p.getCode()))
                .toList();
        if (found.size() != codes.size()) {
            throw new EvyoogException("INVALID_PERMISSION_CODE",
                    "One or more permission codes were not recognized.", HttpStatus.BAD_REQUEST);
        }
        return new HashSet<>(found);
    }

    private RoleResponse toResponse(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .isSystemRole(role.isSystemRole())
                .isActive(role.isActive())
                .permissions(role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet()))
                .updatedBy(role.getUpdatedBy())
                .build();
    }

    private Role findOrThrow(UUID id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role", id));
    }
}
