package com.evyoog.gl.ledger.dto;

import com.evyoog.gl.enterprise.domain.AccountingStandard;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.LedgerCategory;

import java.time.Instant;
import java.util.UUID;

public record LedgerResponse(
        UUID id,
        String code,
        String name,
        String description,
        FinanceMode financeMode,
        LedgerCategory ledgerCategory,
        String functionalCurrency,
        AccountingStandard accountingStandard,
        boolean allowDynamicInsert,
        UUID coaStructureId,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
}
