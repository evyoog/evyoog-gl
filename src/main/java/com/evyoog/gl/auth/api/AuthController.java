package com.evyoog.gl.auth.api;

import com.evyoog.gl.auth.dto.ChangePasswordRequest;
import com.evyoog.gl.auth.dto.LoginRequest;
import com.evyoog.gl.auth.dto.LoginResponse;
import com.evyoog.gl.auth.dto.LogoutRequest;
import com.evyoog.gl.auth.dto.RefreshRequest;
import com.evyoog.gl.auth.dto.UserProfileResponse;
import com.evyoog.gl.auth.service.AuthService;
import com.evyoog.gl.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "AUTH-01 Authentication")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email and password, receive an access and refresh token pair")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate an access/refresh token pair using a valid, unrevoked refresh token")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token")
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user's profile, role assignments and resolved permissions")
    public ApiResponse<UserProfileResponse> me(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ApiResponse.ok(authService.getProfile(userId));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change the authenticated user's own password and revoke their existing refresh tokens")
    public ApiResponse<Void> changePassword(Authentication authentication,
                                             @Valid @RequestBody ChangePasswordRequest request) {
        UUID userId = UUID.fromString(authentication.getName());
        authService.changePassword(userId, request);
        return new ApiResponse<>(true, null, "Password changed successfully", null);
    }
}
