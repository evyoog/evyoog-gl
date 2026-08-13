package com.evyoog.gl.coa.dto;

import com.evyoog.gl.dimension.domain.DimensionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCoaSegmentRequest(

        @NotBlank(message = "code is required")
        @Size(max = 30, message = "code must be at most 30 characters")
        String code,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotNull(message = "dimensionType is required")
        DimensionType dimensionType,

        @NotNull(message = "segmentNumber is required")
        Integer segmentNumber,

        Boolean isRequired
) {
}
