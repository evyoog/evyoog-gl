package com.evyoog.gl.reporting.balancesheet.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder
public record BalanceSheetResponse(
        UUID legalEntityId,
        String legalEntityName,
        String legalEntityCode,
        UUID accountingPeriodId,
        String periodName,
        String fiscalYear,
        String financeMode,
        LocalDate generatedAt,
        List<BalanceSheetLineItem> assetItems,
        List<BalanceSheetLineItem> liabilityItems,
        List<BalanceSheetLineItem> equityItems,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity,
        BigDecimal netIncome,
        BigDecimal totalLiabilitiesAndEquity,
        Boolean isBalanced
) {
}
