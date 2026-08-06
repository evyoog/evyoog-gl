package com.evyoog.gl.combination.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record CreateAccountCombinationRequest(

        @NotNull(message = "ledgerId is required")
        UUID ledgerId,

        @NotNull(message = "legalEntityId is required")
        UUID legalEntityId,

        @NotEmpty(message = "combination is required")
        Map<String, String> combination,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description
) {
}
