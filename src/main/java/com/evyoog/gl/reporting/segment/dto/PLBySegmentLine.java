package com.evyoog.gl.reporting.segment.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;

@Builder
public record PLBySegmentLine(
        String accountCode,
        String accountName,
        String accountQualifier,
        Map<String, BigDecimal> segmentAmounts,
        BigDecimal total
) {
}
