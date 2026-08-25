package com.evyoog.gl.auth.api;

import com.evyoog.gl.auth.dto.ApprovalPolicyRequest;
import com.evyoog.gl.auth.dto.ApprovalPolicyResponse;
import com.evyoog.gl.auth.dto.UpdateApprovalPolicyRequest;
import com.evyoog.gl.auth.service.ApprovalPolicyService;
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
@RequestMapping("/api/v1/auth/approval-policies")
@RequiredArgsConstructor
@Tag(name = "AUTH-01 Approval Policy")
public class ApprovalPolicyController {

    private final ApprovalPolicyService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('gl:approval-policy:manage')")
    @Operation(summary = "Create an approval policy at Legal Entity, Business Unit or Inventory Org scope")
    public ApiResponse<ApprovalPolicyResponse> create(
            @Valid @RequestBody ApprovalPolicyRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.created(service.create(request, userId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('gl:approval-policy:view')")
    @Operation(summary = "List approval policies for a Legal Entity")
    public ApiResponse<List<ApprovalPolicyResponse>> list(@RequestParam UUID legalEntityId) {
        return ApiResponse.ok(service.list(legalEntityId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:approval-policy:manage')")
    @Operation(summary = "Update an approval policy's threshold, approver role or requires-approval flag")
    public ApiResponse<ApprovalPolicyResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ApprovalPolicyRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(id, request, userId));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('gl:approval-policy:manage')")
    @Operation(summary = "Partially update an approval policy's requires-approval flag, threshold, approver role or active flag")
    public ApiResponse<ApprovalPolicyResponse> patch(
            @PathVariable UUID id,
            @RequestBody UpdateApprovalPolicyRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.updatePolicy(id, request, userId));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('gl:approval-policy:manage')")
    @Operation(summary = "Deactivate an approval policy (soft delete)")
    public void delete(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        service.delete(id, userId);
    }
}
