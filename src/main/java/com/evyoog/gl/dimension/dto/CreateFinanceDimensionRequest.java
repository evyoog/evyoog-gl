package com.evyoog.gl.dimension.dto;

import com.evyoog.gl.dimension.domain.DimensionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateFinanceDimensionRequest(

        @NotNull(message = "ledgerId is required")
        UUID ledgerId,

        @NotBlank(message = "code is required")
        @Size(max = 30, message = "code must be at most 30 characters")
        String code,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @NotNull(message = "dimensionType is required")
        DimensionType dimensionType,

        Boolean isRequired,

        Integer displayOrder,

        Boolean isBalancing,

        Integer balancingSequence
) {
}
