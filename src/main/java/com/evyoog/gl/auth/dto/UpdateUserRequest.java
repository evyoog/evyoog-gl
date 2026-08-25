package com.evyoog.gl.auth.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(

        @Size(max = 255, message = "fullName must be at most 255 characters")
        String fullName,

        Boolean isActive
) {
}
