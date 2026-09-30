package com.evyoog.gl.periodstatus.api;

import com.evyoog.gl.common.response.ApiResponse;
import com.evyoog.gl.periodmanagement.service.PeriodManagementService;
import com.evyoog.gl.periodstatus.domain.PeriodStatusEnum;
import com.evyoog.gl.periodstatus.dto.CreatePeriodStatusRequest;
import com.evyoog.gl.periodstatus.dto.PeriodStatusResponse;
import com.evyoog.gl.periodstatus.service.PeriodStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "GL-10 Period Status Control")
public class PeriodStatusController {

    private final PeriodStatusService service;
    private final PeriodManagementService periodManagementService;

    @PostMapping("/api/v1/gl/period-status")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Initialise period status to NOT_OPENED for a Legal Entity and Period")
    public ApiResponse<PeriodStatusResponse> create(
            @Valid @RequestBody CreatePeriodStatusRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping("/api/v1/gl/period-status/{id}")
    @PreAuthorize("hasAuthority('gl:period:view')")
    @Operation(summary = "Get a period status row by id")
    public ApiResponse<PeriodStatusResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping("/api/v1/gl/period-status")
    @PreAuthorize("hasAuthority('gl:period:view')")
    @Operation(summary = "List period status rows for a Legal Entity, optionally filtered by period or status")
    public ApiResponse<List<PeriodStatusResponse>> list(
            @RequestParam UUID legalEntityId,
            @RequestParam(required = false) UUID accountingPeriodId,
            @RequestParam(required = false) PeriodStatusEnum status) {
        return ApiResponse.ok(service.list(legalEntityId, accountingPeriodId, status));
    }

    @PostMapping("/api/v1/gl/period-status/{id}/open")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Transition a period to OPEN. Opening a CLOSED period is a reopen " +
            "(V33 Rule 6) — subject to the same manager-or-above / current-fiscal-year guards.")
    public ApiResponse<PeriodStatusResponse> open(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            Authentication authentication) {
        return ApiResponse.ok(periodManagementService.open(id, actingUserId(authentication), userId));
    }

    @PostMapping("/api/v1/gl/period-status/{id}/future-enterable")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Transition a period to FUTURE_ENTERABLE — THICK mode only")
    public ApiResponse<PeriodStatusResponse> futureEnterable(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.futureEnterable(id, userId));
    }

    @PostMapping("/api/v1/gl/period-status/{id}/close")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Transition a period to CLOSED — all prior periods in the fiscal year must already be closed (V33 Rule 3b)")
    public ApiResponse<PeriodStatusResponse> close(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(periodManagementService.close(id, userId));
    }

    @PostMapping("/api/v1/gl/period-status/{id}/lock")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Transition a period to LOCKED — permanent, THICK mode only")
    public ApiResponse<PeriodStatusResponse> lock(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.lock(id, userId));
    }

    @PostMapping("/api/v1/gl/period-status/{id}/permanently-close")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Permanently close a CLOSED period (V33 Rule 7) — terminal, manager-or-above only, never reopenable")
    public ApiResponse<PeriodStatusResponse> permanentlyClose(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            Authentication authentication) {
        return ApiResponse.ok(periodManagementService.permanentlyClose(id, actingUserId(authentication), userId));
    }

    /**
     * The acting user's real id, from the JWT subject the {@code JwtAuthenticationFilter}
     * sets as the authentication principal — distinct from the {@code X-User-Id} header,
     * which is only ever an audit-trail display string (see {@code performedBy} above).
     * Used by {@code PeriodManagementService} to look up the caller's actual assigned
     * role for the manager-or-above checks in Rules 5b/6c/7b.
     */
    private UUID actingUserId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
