package com.evyoog.gl.dimension.dto;

import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.NormalBalance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record DimensionValueResponse(
        UUID id,
        UUID financeDimensionId,
        String dimensionCode,
        String dimensionName,
        DimensionType dimensionType,
        String code,
        String name,
        String description,
        UUID parentValueId,
        String parentValueCode,
        String parentValueName,
        AccountQualifier accountQualifier,
        boolean isSummary,
        boolean isPostable,
        NormalBalance normalBalance,
        boolean gstApplicable,
        boolean tdsApplicable,
        String tdsSection,
        int displayOrder,
        boolean isDefault,
        boolean isActive,
        UUID counterpartyLegalEntityId,
        String counterpartyLegalEntityName,
        String ccManagerName,
        String ccManagerEmail,
        String ccDepartment,
        LocalDate validFrom,
        LocalDate validTo,
        boolean budgetControlled,
        Map<String, Object> extendedAttributes,
        Instant createdAt,
        Instant updatedAt
) {
}
