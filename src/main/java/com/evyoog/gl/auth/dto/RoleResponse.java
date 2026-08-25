package com.evyoog.gl.auth.dto;

import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder
public record RoleResponse(
        UUID id,
        String code,
        String name,
        String description,
        boolean isSystemRole,
        boolean isActive,
        Set<String> permissions,
        String updatedBy
) {
}
