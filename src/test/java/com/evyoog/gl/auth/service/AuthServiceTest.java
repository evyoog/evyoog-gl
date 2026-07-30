package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.Permission;
import com.evyoog.gl.auth.domain.RefreshToken;
import com.evyoog.gl.auth.domain.Role;
import com.evyoog.gl.auth.domain.User;
import com.evyoog.gl.auth.domain.UserRole;
import com.evyoog.gl.auth.dto.ChangePasswordRequest;
import com.evyoog.gl.auth.dto.LoginRequest;
import com.evyoog.gl.auth.dto.LoginResponse;
import com.evyoog.gl.auth.dto.RefreshRequest;
import com.evyoog.gl.auth.repository.RefreshTokenRepository;
import com.evyoog.gl.auth.repository.UserRepository;
import com.evyoog.gl.auth.repository.UserRoleRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private AuditService auditService;

    @InjectMocks
    private AuthService authService;

    private User activeUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("prashanth@evyoog.com")
                .fullName("Prashanth")
                .passwordHash("hashed")
                .isActive(true)
                .mustChangePwd(false)
                .build();
    }

    @Test
    void testLogin_validCredentials_returnsTokens() {
        User user = activeUser();
        UUID legalEntityId = UUID.randomUUID();
        Permission perm = Permission.builder().code("gl:journal:view").build();
        Role role = Role.builder().permissions(Set.of(perm)).build();
        UserRole userRole = UserRole.builder().role(role).build();

        LoginRequest request = new LoginRequest(user.getEmail(), "plaintext", legalEntityId);

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plaintext", user.getPasswordHash())).thenReturn(true);
        when(userRoleRepository.findByUserIdAndLegalEntityId(user.getId(), legalEntityId))
                .thenReturn(List.of(userRole));
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("access-token");
        when(jwtService.generateRefreshToken()).thenReturn("refresh-token");
        when(jwtService.getJwtExpirationMs()).thenReturn(3600000L);

        LoginResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.legalEntityId()).isEqualTo(legalEntityId);
        assertThat(response.permissions()).containsExactly("gl:journal:view");
    }

    @Test
    void testLogin_wrongPassword_throws401() {
        User user = activeUser();
        LoginRequest request = new LoginRequest(user.getEmail(), "wrong", null);

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "AUTH_FAILED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void testLogin_inactiveUser_throws401() {
        User user = activeUser();
        user.setActive(false);
        LoginRequest request = new LoginRequest(user.getEmail(), "plaintext", null);

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "USER_INACTIVE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void testLogin_noLeAssigned_throws400() {
        User user = activeUser();
        LoginRequest request = new LoginRequest(user.getEmail(), "plaintext", null);

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plaintext", user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NO_LE_ASSIGNED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void testRefresh_validToken_rotatesTokens() {
        User user = activeUser();
        UUID legalEntityId = UUID.randomUUID();
        RefreshToken existing = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash("hash")
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .build();

        RefreshRequest request = new RefreshRequest("raw-refresh-token", legalEntityId);

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));
        when(userRoleRepository.findByUserIdAndLegalEntityId(user.getId(), legalEntityId)).thenReturn(List.of());
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken()).thenReturn("new-refresh-token");
        when(jwtService.getJwtExpirationMs()).thenReturn(3600000L);

        LoginResponse response = authService.refresh(request);

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(existing.isRevoked()).isTrue();
    }

    @Test
    void testRefresh_revokedToken_throws401() {
        RefreshToken revoked = RefreshToken.builder()
                .id(UUID.randomUUID())
                .revoked(true)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        RefreshRequest request = new RefreshRequest("raw-refresh-token", null);

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "TOKEN_REVOKED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void testRefresh_expiredToken_throws401() {
        RefreshToken expired = RefreshToken.builder()
                .id(UUID.randomUUID())
                .revoked(false)
                .expiresAt(Instant.now().minusSeconds(3600))
                .build();
        RefreshRequest request = new RefreshRequest("raw-refresh-token", null);

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "TOKEN_EXPIRED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void testLogout_revokesRefreshToken() {
        RefreshToken existing = RefreshToken.builder()
                .id(UUID.randomUUID())
                .revoked(false)
                .build();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));

        authService.logout("raw-refresh-token");

        assertThat(existing.isRevoked()).isTrue();
    }

    @Test
    void testChangePassword_validCredentials_succeeds() {
        User user = activeUser();
        ChangePasswordRequest request = new ChangePasswordRequest("current", "NewPass123");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current", user.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hashed");
        when(refreshTokenRepository.findByUserIdAndRevokedFalse(user.getId())).thenReturn(List.of());

        authService.changePassword(user.getId(), request);

        assertThat(user.getPasswordHash()).isEqualTo("new-hashed");
        assertThat(user.isMustChangePwd()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void testChangePassword_wrongCurrentPassword_throws400() {
        User user = activeUser();
        ChangePasswordRequest request = new ChangePasswordRequest("wrong", "NewPass123");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(user.getId(), request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CURRENT_PASSWORD")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verify(refreshTokenRepository, never()).findByUserIdAndRevokedFalse(any());
    }

    @Test
    void testChangePassword_weakNewPassword_throws400() {
        User user = activeUser();
        ChangePasswordRequest request = new ChangePasswordRequest("current", "weak");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current", user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword(user.getId(), request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "WEAK_PASSWORD")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verify(userRepository, never()).save(any());
    }

    @Test
    void testChangePassword_revokesRefreshTokens() {
        User user = activeUser();
        ChangePasswordRequest request = new ChangePasswordRequest("current", "NewPass123");

        RefreshToken token1 = RefreshToken.builder().id(UUID.randomUUID()).user(user).revoked(false).build();
        RefreshToken token2 = RefreshToken.builder().id(UUID.randomUUID()).user(user).revoked(false).build();

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current", user.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hashed");
        when(refreshTokenRepository.findByUserIdAndRevokedFalse(user.getId()))
                .thenReturn(List.of(token1, token2));

        authService.changePassword(user.getId(), request);

        assertThat(token1.isRevoked()).isTrue();
        assertThat(token2.isRevoked()).isTrue();
        verify(refreshTokenRepository, times(1)).saveAll(List.of(token1, token2));
    }
}
