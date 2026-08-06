package com.evyoog.gl.combination.dto;

import jakarta.validation.constraints.Size;

public record UpdateAccountCombinationRequest(

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        Boolean isActive
) {
}
