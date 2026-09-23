package com.evyoog.gl.aie.openingbalance.dto;

import lombok.Builder;

import java.util.UUID;

/**
 * One posted (or failed) journal within an Opening Balance import. When the
 * Ledger's COA Structure has a 2nd balancing segment configured
 * ({@code isBalancing=true}, {@code balancingSequence=2}), one of these is
 * produced per distinct segment value found in the uploaded file — otherwise
 * a single result with {@code segmentValue=null} covers the whole file.
 */
@Builder
public record OpeningBalanceJournalResult(
        String segmentValue,
        UUID journalHeaderId,
        String journalNumber,
        int lineCount,
        boolean success,
        String message
) {
}
