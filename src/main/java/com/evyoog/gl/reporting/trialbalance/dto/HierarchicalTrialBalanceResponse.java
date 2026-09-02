package com.evyoog.gl.reporting.trialbalance.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Builder
public record HierarchicalTrialBalanceResponse(
        UUID legalEntityId,
        String legalEntityName,
        UUID accountingPeriodId,
        String periodName,
        String fiscalYear,
        String generatedAt,
        List<HierarchicalTrialBalanceLine> lines,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean isBalanced,
        int totalAccounts,
        int accountsWithActivity
) {
}
