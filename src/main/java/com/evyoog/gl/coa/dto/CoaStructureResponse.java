package com.evyoog.gl.coa.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CoaStructureResponse(
        UUID id,
        UUID businessGroupId,
        String code,
        String name,
        String description,
        String separator,
        int segmentCount,
        List<CoaSegmentSummary> segments,
        long assignedLedgerCount,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt,
        String createdBy
) {
}
