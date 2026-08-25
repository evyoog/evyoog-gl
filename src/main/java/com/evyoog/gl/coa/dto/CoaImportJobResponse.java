package com.evyoog.gl.coa.dto;

import com.evyoog.gl.coa.domain.ImportJobStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CoaImportJobResponse(
        UUID id,
        UUID ledgerId,
        UUID financeDimensionId,
        ImportJobStatus status,
        String fileName,
        Integer totalRows,
        Integer processedRows,
        Integer successRows,
        Integer errorRows,
        Map<String, Object> errorDetails,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy
) {
}
