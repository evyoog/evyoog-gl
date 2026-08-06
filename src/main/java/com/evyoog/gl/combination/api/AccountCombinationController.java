package com.evyoog.gl.combination.api;

import com.evyoog.gl.combination.dto.AccountCombinationResponse;
import com.evyoog.gl.combination.dto.CreateAccountCombinationRequest;
import com.evyoog.gl.combination.dto.UpdateAccountCombinationRequest;
import com.evyoog.gl.combination.service.AccountCombinationService;
import com.evyoog.gl.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gl/account-combinations")
@RequiredArgsConstructor
@Tag(name = "Account Combination Registry")
public class AccountCombinationController {

    private final AccountCombinationService service;

    @GetMapping
    @PreAuthorize("hasAuthority('gl:accounts:view')")
    @Operation(summary = "List account combinations for a ledger/legal entity, optionally filtered by segment")
    public ApiResponse<List<AccountCombinationResponse>> list(
            @RequestParam UUID ledgerId,
            @RequestParam UUID legalEntityId,
            @RequestParam(required = false) String costCentre,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) Boolean isActive) {
        return ApiResponse.ok(service.list(ledgerId, legalEntityId, costCentre, product, isActive));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:accounts:create')")
    @Operation(summary = "Manually pre-approve an account combination")
    public ApiResponse<AccountCombinationResponse> create(
            @Valid @RequestBody CreateAccountCombinationRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:accounts:edit')")
    @Operation(summary = "Update an account combination's description/active status")
    public ApiResponse<AccountCombinationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAccountCombinationRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(id, request, userId));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('gl:accounts:edit')")
    @Operation(summary = "Deactivate an account combination (isActive = false)")
    public ApiResponse<AccountCombinationResponse> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.deactivate(id, userId));
    }
}
