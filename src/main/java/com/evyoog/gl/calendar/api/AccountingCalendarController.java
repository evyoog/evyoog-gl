package com.evyoog.gl.calendar.api;

import com.evyoog.gl.calendar.dto.AccountingCalendarResponse;
import com.evyoog.gl.calendar.dto.CreateCalendarRequest;
import com.evyoog.gl.calendar.dto.UpdateCalendarRequest;
import com.evyoog.gl.calendar.service.AccountingCalendarService;
import com.evyoog.gl.common.response.ApiResponse;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gl/accounting-calendars")
@RequiredArgsConstructor
@Tag(name = "GL-08 Accounting Calendar")
public class AccountingCalendarController {

    private final AccountingCalendarService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Create the Accounting Calendar for a Ledger and auto-generate its initial fiscal year periods")
    public ApiResponse<AccountingCalendarResponse> create(
            @Valid @RequestBody CreateCalendarRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:period:view')")
    @Operation(summary = "Get an Accounting Calendar by id")
    public ApiResponse<AccountingCalendarResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('gl:period:view')")
    @Operation(summary = "Get the Accounting Calendar for a Ledger, or all Calendars when ledgerId is omitted")
    public ApiResponse<?> getByLedgerId(@RequestParam(required = false) UUID ledgerId) {
        if (ledgerId == null) {
            return ApiResponse.ok(service.getAll());
        }
        return ApiResponse.ok(service.getByLedgerId(ledgerId).orElse(null));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Update the Calendar's name/description (fiscal start cannot change after periods exist)")
    public ApiResponse<AccountingCalendarResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCalendarRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(id, request, userId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Soft-delete an Accounting Calendar (rejected once its periods have been generated)")
    public ApiResponse<Void> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        service.deactivate(id, userId);
        return ApiResponse.ok(null);
    }
}
