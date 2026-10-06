package com.evyoog.gl.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Full-replace (PUT) body for a Ledger's editable fields. Only name and description are
 * editable — code, financeMode, ledgerCategory, functionalCurrency, accountingStandard and
 * coaStructureId are structural and are silently ignored if sent (unknown JSON properties
 * are dropped by Jackson). Unlike {@link UpdateLedgerRequest} (PATCH, partial), name is
 * required here and a null description clears it.
 */
public record ReplaceLedgerRequest(

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description
) {
}
