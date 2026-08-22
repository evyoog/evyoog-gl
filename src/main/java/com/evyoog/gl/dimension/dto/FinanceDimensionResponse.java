package com.evyoog.gl.dimension.dto;

import com.evyoog.gl.dimension.domain.DimensionType;

import java.time.Instant;
import java.util.UUID;

public record FinanceDimensionResponse(
        UUID id,
        UUID ledgerId,
        String ledgerName,
        UUID coaStructureId,
        String code,
        String name,
        String description,
        DimensionType dimensionType,
        boolean isRequired,
        int displayOrder,
        boolean isActive,
        long valueCount,
        Instant createdAt,
        Instant updatedAt,
        boolean isBalancing,
        Integer balancingSequence
) {
}
