package com.evyoog.gl.coa.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateCoaStructureRequest(

        @NotNull(message = "businessGroupId is required")
        UUID businessGroupId,

        @NotBlank(message = "code is required")
        @Size(max = 50, message = "code must be at most 50 characters")
        String code,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Size(max = 5, message = "separator must be at most 5 characters")
        String separator,

        @NotEmpty(message = "at least one segment is required")
        @Valid
        List<CreateCoaSegmentRequest> segments
) {
}
