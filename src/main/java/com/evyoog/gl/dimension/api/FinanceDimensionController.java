package com.evyoog.gl.dimension.api;

import com.evyoog.gl.common.response.ApiResponse;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.dto.CreateFinanceDimensionRequest;
import com.evyoog.gl.dimension.dto.FinanceDimensionResponse;
import com.evyoog.gl.dimension.dto.UpdateFinanceDimensionRequest;
import com.evyoog.gl.dimension.service.FinanceDimensionService;
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
@RequestMapping("/api/v1/gl/finance-dimensions")
@RequiredArgsConstructor
@Tag(name = "GL-04 Finance Dimensions")
public class FinanceDimensionController {

    private final FinanceDimensionService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Create a finance dimension")
    public ApiResponse<FinanceDimensionResponse> create(
            @Valid @RequestBody CreateFinanceDimensionRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:dimension:view')")
    @Operation(summary = "Get a finance dimension by id")
    public ApiResponse<FinanceDimensionResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('gl:dimension:view')")
    @Operation(summary = "List finance dimensions, optionally filtered by ledger, COA Structure, and dimension type")
    public ApiResponse<List<FinanceDimensionResponse>> list(
            @RequestParam(required = false) UUID ledgerId,
            @RequestParam(required = false) UUID coaStructureId,
            @RequestParam(required = false) DimensionType dimensionType) {
        return ApiResponse.ok(service.list(ledgerId, coaStructureId, dimensionType));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Update mutable fields of a finance dimension")
    public ApiResponse<FinanceDimensionResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFinanceDimensionRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(id, request, userId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:dimension:manage')")
    @Operation(summary = "Soft-delete a finance dimension (isActive = false)")
    public ApiResponse<Void> deactivate(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        service.deactivate(id, userId);
        return ApiResponse.ok(null);
    }
}
