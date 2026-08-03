package com.evyoog.gl.reporting.ledger.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Builder
public record AccountLedgerEntry(
        UUID lineId,
        UUID journalHeaderId,
        String journalNumber,
        LocalDate glDate,
        LocalDate accountingDate,
        String journalDescription,
        String lineDescription,
        String journalSourceCode,
        String journalCategoryCode,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        BigDecimal runningBalance,
        Boolean gstApplicable,
        String gstType,
        Boolean tdsApplicable,
        String tdsSection,
        Map<String, String> accountCombination,
        Instant createdAt
) {
}
