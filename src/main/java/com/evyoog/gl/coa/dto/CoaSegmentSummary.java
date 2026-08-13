package com.evyoog.gl.coa.dto;

import com.evyoog.gl.dimension.domain.DimensionType;

import java.util.UUID;

public record CoaSegmentSummary(
        UUID id,
        String code,
        String name,
        DimensionType dimensionType,
        int segmentNumber,
        boolean isRequired,
        long valueCount
) {
}
