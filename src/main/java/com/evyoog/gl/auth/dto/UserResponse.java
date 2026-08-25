package com.evyoog.gl.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        boolean isActive,
        boolean mustChangePwd,
        Instant lastLoginAt,
        Instant createdAt,
        String updatedBy
) {
}
