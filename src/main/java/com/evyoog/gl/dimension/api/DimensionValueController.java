package com.evyoog.gl.dimension.api;

import com.evyoog.gl.common.response.ApiResponse;
import com.evyoog.gl.dimension.dto.CreateDimensionValueRequest;
import com.evyoog.gl.dimension.dto.DimensionValueResponse;
import com.evyoog.gl.dimension.dto.UpdateDimensionValueRequest;
import com.evyoog.gl.dimension.service.DimensionValueService;
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
@RequestMapping("/api/v1/gl/dimension-values")
@RequiredArgsConstructor
@Tag(name = "GL-04 Dimension Values")
public class DimensionValueController {

    private final DimensionValueService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Create a dimension value")
    public ApiResponse<DimensionValueResponse> create(
            @Valid @RequestBody CreateDimensionValueRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:dimension:view')")
    @Operation(summary = "Get a dimension value by id")
    public ApiResponse<DimensionValueResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('gl:dimension:view')")
    @Operation(summary = "List dimension values for a finance dimension, optionally filtered by parent value")
    public ApiResponse<List<DimensionValueResponse>> list(
            @RequestParam UUID financeDimensionId,
            @RequestParam(required = false) UUID parentValueId) {
        return ApiResponse.ok(service.list(financeDimensionId, parentValueId));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('gl:dimension:view')")
    @Operation(summary = "Search dimension values by ledger and code")
    public ApiResponse<List<DimensionValueResponse>> search(
            @RequestParam UUID ledgerId,
            @RequestParam String code) {
        return ApiResponse.ok(service.search(ledgerId, code));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Update mutable fields of a dimension value")
    public ApiResponse<DimensionValueResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDimensionValueRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(id, request, userId));
    }

    @PostMapping("/{id}/set-default")
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Set this value as the default for its dimension, clearing any existing default")
    public ApiResponse<DimensionValueResponse> setDefault(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.setDefault(id, userId));
    }

    @PostMapping("/{id}/clear-default")
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Clear the default flag on this value")
    public ApiResponse<DimensionValueResponse> clearDefault(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.clearDefault(id, userId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Soft-delete a dimension value (isActive = false)")
    public ApiResponse<Void> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        service.deactivate(id, userId);
        return ApiResponse.ok(null);
    }
}
