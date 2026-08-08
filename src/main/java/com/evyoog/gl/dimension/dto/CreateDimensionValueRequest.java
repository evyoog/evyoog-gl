package com.evyoog.gl.dimension.dto;

import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.NormalBalance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record CreateDimensionValueRequest(

        @NotNull(message = "financeDimensionId is required")
        UUID financeDimensionId,

        @NotBlank(message = "code is required")
        @Size(max = 30, message = "code must be at most 30 characters")
        String code,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        UUID parentValueId,

        AccountQualifier accountQualifier,

        Boolean isSummary,

        Boolean isPostable,

        NormalBalance normalBalance,

        Boolean gstApplicable,

        Boolean tdsApplicable,

        @Size(max = 10, message = "tdsSection must be at most 10 characters")
        String tdsSection,

        Integer displayOrder,

        Boolean isDefault,

        // Intercompany — required when the owning Finance Dimension is INTERCOMPANY
        UUID counterpartyLegalEntityId,

        // Cost Centre metadata — informational Phase 1
        String ccManagerName,

        String ccManagerEmail,

        String ccDepartment,

        // Date range — all dimension types
        LocalDate validFrom,

        LocalDate validTo,

        // Budget control — Phase 1 stored, Phase 2 enforced
        Boolean budgetControlled,

        Map<String, Object> extendedAttributes
) {
}
