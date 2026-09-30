package com.evyoog.gl.periodstatus.dto;

import jakarta.validation.constraints.NotBlank;

public record ReopenPeriodStatusRequest(

        @NotBlank(message = "reopenedBy is required")
        String reopenedBy,

        String reason
) {
}
