package com.evyoog.gl.dimension.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateDimensionValueRequest(

        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        // When provided, becomes the account's new parent — validated against
        // circular reference (an account cannot become its own ancestor).
        UUID parentValueId,

        Boolean isSummary,

        Boolean isPostable,

        Boolean gstApplicable,

        Boolean tdsApplicable,

        @Size(max = 10, message = "tdsSection must be at most 10 characters")
        String tdsSection,

        Integer displayOrder,

        Boolean isDefault,

        UUID counterpartyLegalEntityId,

        String ccManagerName,

        String ccManagerEmail,

        String ccDepartment,

        LocalDate validFrom,

        LocalDate validTo,

        Boolean budgetControlled
) {
}
