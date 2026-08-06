package com.evyoog.gl.ledger.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateDynamicInsertRequest(

        @NotNull(message = "allowDynamicInsert is required")
        Boolean allowDynamicInsert
) {
}
