package com.evyoog.gl.aie.openingbalance.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record OpeningBalancePreviewLine(
        int lineNumber,
        String accountCode,
        String accountName,
        String accountQualifier,
        String normalBalance,
        String costCentreCode,
        String productCode,
        BigDecimal balance,
        BigDecimal drAmount,
        BigDecimal crAmount,
        String description,
        String errorMessage
) {
}
