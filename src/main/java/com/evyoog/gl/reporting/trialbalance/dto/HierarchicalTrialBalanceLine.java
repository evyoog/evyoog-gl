package com.evyoog.gl.reporting.trialbalance.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Builder
public record HierarchicalTrialBalanceLine(
        UUID accountId,
        String accountCode,
        String accountName,
        String accountQualifier,
        String normalBalance,
        boolean isSummary,
        boolean isPostable,
        int depth,
        BigDecimal beginningBalance,
        BigDecimal periodToDateDr,
        BigDecimal periodToDateCr,
        BigDecimal yearToDateDr,
        BigDecimal yearToDateCr,
        BigDecimal endingBalance,
        BigDecimal debitBalance,
        BigDecimal creditBalance,
        List<HierarchicalTrialBalanceLine> children
) {
}
