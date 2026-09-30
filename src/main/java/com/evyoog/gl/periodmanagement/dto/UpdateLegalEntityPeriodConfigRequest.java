package com.evyoog.gl.periodmanagement.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateLegalEntityPeriodConfigRequest(

        @NotNull(message = "maxOpenPeriods is required")
        @Min(value = 1, message = "maxOpenPeriods must be at least 1")
        Integer maxOpenPeriods,

        @NotNull(message = "futurePeriodLimit is required")
        @Min(value = 0, message = "futurePeriodLimit must be between 0 and 3")
        @Max(value = 3, message = "futurePeriodLimit must be between 0 and 3")
        Integer futurePeriodLimit,

        @NotNull(message = "adjustmentPeriodEnabled is required")
        Boolean adjustmentPeriodEnabled
) {
}
