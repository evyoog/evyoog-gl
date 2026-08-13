package com.evyoog.gl.coa.dto;

import jakarta.validation.constraints.Size;

public record UpdateCoaStructureRequest(

        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        Boolean isActive
) {
}
