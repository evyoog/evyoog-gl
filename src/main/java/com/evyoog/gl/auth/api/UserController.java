package com.evyoog.gl.auth.api;

import com.evyoog.gl.auth.dto.AssignRoleRequest;
import com.evyoog.gl.auth.dto.CreateUserRequest;
import com.evyoog.gl.auth.dto.ResetPasswordResponse;
import com.evyoog.gl.auth.dto.UserResponse;
import com.evyoog.gl.auth.dto.UserRoleAssignmentResponse;
import com.evyoog.gl.auth.service.UserService;
import com.evyoog.gl.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/users")
@RequiredArgsConstructor
@Tag(name = "AUTH-01 User Management")
public class UserController {

    private final UserService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:users:create')")
    @Operation(summary = "Create a user and assign their initial role at a Legal Entity")
    public ApiResponse<UserResponse> create(
            @Valid @RequestBody CreateUserRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('gl:users:view')")
    @Operation(summary = "List users, optionally filtered by Legal Entity")
    public ApiResponse<List<UserResponse>> list(@RequestParam(required = false) UUID legalEntityId) {
        return ApiResponse.ok(service.list(legalEntityId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:users:view')")
    @Operation(summary = "Get a user by id")
    public ApiResponse<UserResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('gl:users:edit')")
    @Operation(summary = "Deactivate a user")
    public ApiResponse<UserResponse> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.deactivate(id, userId));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('gl:users:edit')")
    @Operation(summary = "Reset a user's password to a random temporary value — forces change on next login")
    public ApiResponse<ResetPasswordResponse> resetPassword(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.resetPassword(id, userId));
    }

    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:users:edit')")
    @Operation(summary = "Assign a role to a user, scoped to a Legal Entity")
    public ApiResponse<UserRoleAssignmentResponse> assignRole(
            @PathVariable UUID id,
            @Valid @RequestBody AssignRoleRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.assignRole(id, request, userId));
    }

    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('gl:users:view')")
    @Operation(summary = "List all role assignments for a user")
    public ApiResponse<List<UserRoleAssignmentResponse>> listRoles(@PathVariable UUID id) {
        return ApiResponse.ok(service.listRoles(id));
    }

    @DeleteMapping("/{id}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('gl:users:edit')")
    @Operation(summary = "Remove a role assignment from a user")
    public void removeRole(
            @PathVariable UUID id,
            @PathVariable UUID roleId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        service.removeRole(id, roleId, userId);
    }
}
