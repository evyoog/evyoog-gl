package com.evyoog.gl.aie.openingbalance.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record OpeningBalancePreviewResponse(
        int totalLines,
        int validLines,
        int errorLines,
        BigDecimal totalDr,
        BigDecimal totalCr,
        boolean isBalanced,
        BigDecimal imbalanceAmount,
        List<OpeningBalancePreviewLine> lines,
        List<String> errors
) {
}
