package com.evyoog.gl.ledger.api;

import com.evyoog.gl.common.response.ApiResponse;
import com.evyoog.gl.ledger.dto.CreateLedgerRequest;
import com.evyoog.gl.ledger.dto.LedgerResponse;
import com.evyoog.gl.ledger.dto.UpdateDynamicInsertRequest;
import com.evyoog.gl.ledger.dto.UpdateFinanceModeRequest;
import com.evyoog.gl.ledger.dto.UpdateLedgerRequest;
import com.evyoog.gl.ledger.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gl/ledgers")
@RequiredArgsConstructor
@Tag(name = "GL-03 Ledgers")
public class LedgerController {

    private final LedgerService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Create a ledger")
    public ApiResponse<LedgerResponse> create(
            @Valid @RequestBody CreateLedgerRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:ledger:view')")
    @Operation(summary = "Get a ledger by id")
    public ApiResponse<LedgerResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('gl:ledger:view')")
    @Operation(summary = "List ledgers, optionally filtered by business group")
    public ApiResponse<List<LedgerResponse>> list(
            @RequestParam(required = false) UUID businessGroupId) {
        return ApiResponse.ok(service.list(businessGroupId));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Update mutable fields of a ledger")
    public ApiResponse<LedgerResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLedgerRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(id, request, userId));
    }

    @PatchMapping("/{id}/finance-mode")
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Upgrade a ledger's finance mode (EVENT_ONLY -> THIN -> THICK only)")
    public ApiResponse<LedgerResponse> upgradeFinanceMode(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFinanceModeRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.upgradeFinanceMode(id, request, userId));
    }

    @PatchMapping("/{id}/dynamic-insert")
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Toggle whether unknown account combinations are auto-registered on posting")
    public ApiResponse<LedgerResponse> updateDynamicInsert(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDynamicInsertRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.updateDynamicInsert(id, request, userId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Soft-delete a ledger (isActive = false)")
    public ApiResponse<Void> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        service.deactivate(id, userId);
        return ApiResponse.ok(null);
    }
}
