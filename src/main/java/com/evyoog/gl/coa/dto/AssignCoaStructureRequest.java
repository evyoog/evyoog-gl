package com.evyoog.gl.coa.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignCoaStructureRequest(

        @NotNull(message = "ledgerId is required")
        UUID ledgerId
) {
}
