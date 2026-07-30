package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.Permission;
import com.evyoog.gl.auth.domain.RefreshToken;
import com.evyoog.gl.auth.domain.User;
import com.evyoog.gl.auth.domain.UserRole;
import com.evyoog.gl.auth.dto.ChangePasswordRequest;
import com.evyoog.gl.auth.dto.LoginRequest;
import com.evyoog.gl.auth.dto.LoginResponse;
import com.evyoog.gl.auth.dto.RefreshRequest;
import com.evyoog.gl.auth.dto.UserProfileResponse;
import com.evyoog.gl.auth.dto.UserRoleAssignmentResponse;
import com.evyoog.gl.auth.repository.RefreshTokenRepository;
import com.evyoog.gl.auth.repository.UserRepository;
import com.evyoog.gl.auth.repository.UserRoleRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern UPPERCASE_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    @Value("${evyoog.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @Transactional
    public LoginResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new EvyoogException("AUTH_FAILED",
                        "Invalid email or password.", HttpStatus.UNAUTHORIZED));

        if (!user.isActive()) {
            throw new EvyoogException("USER_INACTIVE", "Account is deactivated.", HttpStatus.UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new EvyoogException("AUTH_FAILED", "Invalid email or password.", HttpStatus.UNAUTHORIZED);
        }

        UUID legalEntityId = resolveLegalEntityId(user.getId(), request.legalEntityId());
        Set<String> permissions = resolvePermissions(user.getId(), legalEntityId);

        String accessToken = jwtService.generateAccessToken(user, legalEntityId, permissions);
        String refreshTokenValue = jwtService.generateRefreshToken();
        storeRefreshToken(user, refreshTokenValue);

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenValue)
                .expiresIn(jwtService.getJwtExpirationMs() / 1000)
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .legalEntityId(legalEntityId)
                .permissions(permissions)
                .mustChangePwd(user.isMustChangePwd())
                .build();
    }

    @Transactional
    public LoginResponse refresh(RefreshRequest request) {
        String tokenHash = hashToken(request.refreshToken());

        RefreshToken rt = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new EvyoogException("INVALID_REFRESH_TOKEN",
                        "Refresh token not found.", HttpStatus.UNAUTHORIZED));

        if (rt.isRevoked()) {
            throw new EvyoogException("TOKEN_REVOKED", "Refresh token has been revoked.", HttpStatus.UNAUTHORIZED);
        }

        if (rt.getExpiresAt().isBefore(Instant.now())) {
            throw new EvyoogException("TOKEN_EXPIRED", "Refresh token has expired.", HttpStatus.UNAUTHORIZED);
        }

        rt.setRevoked(true);
        refreshTokenRepository.save(rt);

        User user = rt.getUser();
        UUID legalEntityId = resolveLegalEntityId(user.getId(), request.legalEntityId());
        Set<String> permissions = resolvePermissions(user.getId(), legalEntityId);

        String newAccessToken = jwtService.generateAccessToken(user, legalEntityId, permissions);
        String newRefreshToken = jwtService.generateRefreshToken();
        storeRefreshToken(user, newRefreshToken);

        return LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(jwtService.getJwtExpirationMs() / 1000)
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .legalEntityId(legalEntityId)
                .permissions(permissions)
                .mustChangePwd(user.isMustChangePwd())
                .build();
    }

    @Transactional
    public void logout(String refreshToken) {
        String hash = hashToken(refreshToken);
        refreshTokenRepository.findByTokenHash(hash)
                .ifPresent(rt -> {
                    rt.setRevoked(true);
                    refreshTokenRepository.save(rt);
                });
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        List<UserRole> assignments = userRoleRepository.findByUserId(userId);

        List<UserRoleAssignmentResponse> roles = assignments.stream()
                .map(ur -> new UserRoleAssignmentResponse(
                        ur.getId(), ur.getRole().getId(), ur.getRole().getCode(), ur.getRole().getName(),
                        ur.getLegalEntity().getId(), ur.getLegalEntity().getCode()))
                .toList();

        Set<String> permissions = assignments.stream()
                .flatMap(ur -> ur.getRole().getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toSet());

        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .mustChangePwd(user.isMustChangePwd())
                .roles(roles)
                .permissions(permissions)
                .build();
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new EvyoogException("INVALID_CURRENT_PASSWORD",
                    "Current password is incorrect.", HttpStatus.BAD_REQUEST);
        }

        validateNewPassword(request.newPassword());

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePwd(false);
        userRepository.save(user);

        List<RefreshToken> activeTokens = refreshTokenRepository.findByUserIdAndRevokedFalse(userId);
        activeTokens.forEach(rt -> rt.setRevoked(true));
        refreshTokenRepository.saveAll(activeTokens);

        auditService.log(AuditAction.UPDATE, "auth_user", user.getId(), null, "password changed", userId.toString());
    }

    private void validateNewPassword(String newPassword) {
        if (newPassword.length() < 8
                || !UPPERCASE_PATTERN.matcher(newPassword).find()
                || !DIGIT_PATTERN.matcher(newPassword).find()) {
            throw new EvyoogException("WEAK_PASSWORD",
                    "Password must be at least 8 characters with one uppercase letter and one number.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private UUID resolveLegalEntityId(UUID userId, UUID requestedLegalEntityId) {
        if (requestedLegalEntityId != null) {
            return requestedLegalEntityId;
        }
        return userRoleRepository.findFirstLegalEntityByUserId(userId)
                .orElseThrow(() -> new EvyoogException("NO_LE_ASSIGNED",
                        "User has no Legal Entity assigned.", HttpStatus.BAD_REQUEST));
    }

    private Set<String> resolvePermissions(UUID userId, UUID legalEntityId) {
        return userRoleRepository.findByUserIdAndLegalEntityId(userId, legalEntityId).stream()
                .flatMap(ur -> ur.getRole().getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toSet());
    }

    private void storeRefreshToken(User user, String refreshTokenValue) {
        RefreshToken rt = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(refreshTokenValue))
                .expiresAt(Instant.now().plusMillis(refreshExpirationMs))
                .build();
        refreshTokenRepository.save(rt);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
