package com.evyoog.gl.periodmanagement.api;

import com.evyoog.gl.common.response.ApiResponse;
import com.evyoog.gl.periodmanagement.dto.LegalEntityPeriodConfigResponse;
import com.evyoog.gl.periodmanagement.dto.UpdateLegalEntityPeriodConfigRequest;
import com.evyoog.gl.periodmanagement.service.LegalEntityPeriodConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Period Management — Legal Entity Period Config")
public class LegalEntityPeriodConfigController {

    private final LegalEntityPeriodConfigService service;

    @GetMapping("/api/v1/gl/legal-entity-period-config/{legalEntityId}")
    @PreAuthorize("hasAuthority('gl:period:view')")
    @Operation(summary = "Get the period-management configuration for a Legal Entity " +
            "(max open periods, future period limit, adjustment period toggle)")
    public ApiResponse<LegalEntityPeriodConfigResponse> getByLegalEntityId(@PathVariable UUID legalEntityId) {
        return ApiResponse.ok(service.getByLegalEntityId(legalEntityId));
    }

    @PutMapping("/api/v1/gl/legal-entity-period-config/{legalEntityId}")
    @PreAuthorize("hasAuthority('gl:period:manage')")
    @Operation(summary = "Replace the period-management configuration for a Legal Entity")
    public ApiResponse<LegalEntityPeriodConfigResponse> update(
            @PathVariable UUID legalEntityId,
            @Valid @RequestBody UpdateLegalEntityPeriodConfigRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId) {
        return ApiResponse.ok(service.update(legalEntityId, request, userId));
    }
}
