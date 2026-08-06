package com.evyoog.gl.combination.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AccountCombinationResponse(
        UUID id,
        UUID ledgerId,
        String ledgerName,
        UUID legalEntityId,
        String legalEntityName,
        Map<String, String> combination,
        String combinationCode,
        String description,
        boolean isActive,
        boolean isDynamic,
        Instant firstUsedAt,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
