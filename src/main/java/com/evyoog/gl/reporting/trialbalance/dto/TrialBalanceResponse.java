package com.evyoog.gl.reporting.trialbalance.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder
public record TrialBalanceResponse(
        UUID legalEntityId,
        String legalEntityName,
        String legalEntityCode,
        UUID accountingPeriodId,
        String periodName,
        String fiscalYear,
        String financeMode,
        LocalDate generatedAt,
        List<TrialBalanceLine> lines,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean isBalanced,
        Integer totalAccounts,
        Integer accountsWithActivity,
        SegmentFilters segmentFilters
) {
}
