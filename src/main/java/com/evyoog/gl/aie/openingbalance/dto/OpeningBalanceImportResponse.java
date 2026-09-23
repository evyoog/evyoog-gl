package com.evyoog.gl.aie.openingbalance.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record OpeningBalanceImportResponse(
        boolean success,
        UUID journalHeaderId,
        String journalNumber,
        int totalLines,
        int postedLines,
        int journalCount,
        List<OpeningBalanceJournalResult> journals,
        String message,
        List<String> errors
) {
}
