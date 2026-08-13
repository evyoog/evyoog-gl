package com.evyoog.gl.coa.api;

import com.evyoog.gl.coa.dto.AssignCoaStructureRequest;
import com.evyoog.gl.coa.dto.CoaStructureResponse;
import com.evyoog.gl.coa.dto.CreateCoaSegmentRequest;
import com.evyoog.gl.coa.dto.CreateCoaStructureRequest;
import com.evyoog.gl.coa.dto.UpdateCoaStructureRequest;
import com.evyoog.gl.coa.service.CoaStructureService;
import com.evyoog.gl.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/gl/coa-structures")
@RequiredArgsConstructor
@Tag(name = "COA Structure")
public class CoaStructureController {

    private final CoaStructureService service;

    @GetMapping
    @PreAuthorize("hasAuthority('gl:ledger:view')")
    @Operation(summary = "List COA Structures for a Business Group")
    public ApiResponse<List<CoaStructureResponse>> list(@RequestParam UUID businessGroupId) {
        return ApiResponse.ok(service.listByBusinessGroup(businessGroupId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Create a COA Structure with its segments")
    public ApiResponse<CoaStructureResponse> create(
            @Valid @RequestBody CreateCoaStructureRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:ledger:view')")
    @Operation(summary = "Get a COA Structure with its segments")
    public ApiResponse<CoaStructureResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Update a COA Structure's metadata")
    public ApiResponse<CoaStructureResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCoaStructureRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(id, request, userId));
    }

    @GetMapping("/by-ledger/{ledgerId}")
    @PreAuthorize("hasAuthority('gl:ledger:view')")
    @Operation(summary = "Get the COA Structure assigned to a Ledger")
    public ApiResponse<CoaStructureResponse> getByLedgerId(@PathVariable UUID ledgerId) {
        return ApiResponse.ok(service.getByLedgerId(ledgerId));
    }

    @PostMapping("/{id}/assign-ledger")
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Assign a COA Structure to a Ledger")
    public ApiResponse<CoaStructureResponse> assignToLedger(
            @PathVariable UUID id,
            @Valid @RequestBody AssignCoaStructureRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.assignToLedger(id, request.ledgerId(), userId));
    }

    @PostMapping("/{id}/segments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Add a segment to a COA Structure")
    public ApiResponse<CoaStructureResponse> addSegment(
            @PathVariable UUID id,
            @Valid @RequestBody CreateCoaSegmentRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.addSegment(id, request, userId));
    }

    @DeleteMapping("/{id}/segments/{financeDimensionId}")
    @PreAuthorize("hasAuthority('gl:ledger:manage')")
    @Operation(summary = "Remove a segment from a COA Structure (deactivate)")
    public ApiResponse<CoaStructureResponse> removeSegment(
            @PathVariable UUID id,
            @PathVariable UUID financeDimensionId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.removeSegment(id, financeDimensionId, userId));
    }

    @GetMapping("/{id}/combination-format")
    @PreAuthorize("hasAuthority('gl:ledger:view')")
    @Operation(summary = "Preview the account combination code format for a COA Structure")
    public ApiResponse<String> getCombinationFormat(@PathVariable UUID id) {
        return ApiResponse.ok(service.getCombinationFormat(id));
    }
}
