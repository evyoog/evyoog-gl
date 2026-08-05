package com.evyoog.gl.reporting.trialbalance.api;

import com.evyoog.gl.common.response.ApiResponse;
import com.evyoog.gl.reporting.trialbalance.dto.TrialBalanceResponse;
import com.evyoog.gl.reporting.trialbalance.service.TrialBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "GL-22 Trial Balance")
public class TrialBalanceController {

    private final TrialBalanceService trialBalanceService;

    @GetMapping("/api/v1/gl/reports/trial-balance")
    @PreAuthorize("hasAuthority('gl:trial-balance:view')")
    @Operation(summary = "Generate the Trial Balance for a Legal Entity and Period, "
            + "optionally filtered by Cost Centre and/or Product segment")
    public ApiResponse<TrialBalanceResponse> getTrialBalance(
            @RequestParam UUID legalEntityId,
            @RequestParam UUID periodId,
            @RequestParam(required = false) String costCentre,
            @RequestParam(required = false) String product) {
        return ApiResponse.ok(trialBalanceService.generate(legalEntityId, periodId, costCentre, product));
    }

    @GetMapping("/api/v1/gl/reports/trial-balance/export")
    @PreAuthorize("hasAuthority('gl:trial-balance:export')")
    @Operation(summary = "Export the Trial Balance (Phase 1: JSON only — PDF/EXCEL generation is Phase 2)")
    public ApiResponse<TrialBalanceResponse> exportTrialBalance(
            @RequestParam UUID legalEntityId,
            @RequestParam UUID periodId,
            @RequestParam(required = false, defaultValue = "PDF") String format,
            @RequestParam(required = false) String costCentre,
            @RequestParam(required = false) String product) {
        return new ApiResponse<>(true, trialBalanceService.generate(legalEntityId, periodId, costCentre, product),
                "Export format '" + format + "' is not yet implemented — returning structured JSON. "
                        + "PDF/Excel generation is planned for Phase 2.",
                null);
    }
}
