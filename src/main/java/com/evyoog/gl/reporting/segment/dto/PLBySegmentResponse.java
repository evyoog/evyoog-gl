package com.evyoog.gl.reporting.segment.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder
public record PLBySegmentResponse(
        UUID legalEntityId,
        String legalEntityName,
        UUID accountingPeriodId,
        String periodName,
        String fiscalYear,
        String segmentType,
        List<String> segments,
        List<PLBySegmentLine> revenueLines,
        List<PLBySegmentLine> expenseLines,
        Map<String, BigDecimal> totalRevenue,
        Map<String, BigDecimal> totalExpenses,
        Map<String, BigDecimal> netIncome,
        LocalDate generatedAt
) {
}
