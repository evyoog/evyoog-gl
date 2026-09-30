package com.evyoog.gl.periodmanagement.dto;

import java.time.Instant;
import java.util.UUID;

public record LegalEntityPeriodConfigResponse(
        UUID id,
        UUID legalEntityId,
        Integer maxOpenPeriods,
        Integer futurePeriodLimit,
        Boolean adjustmentPeriodEnabled,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
