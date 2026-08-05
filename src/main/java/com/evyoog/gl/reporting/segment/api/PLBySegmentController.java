package com.evyoog.gl.reporting.segment.api;

import com.evyoog.gl.common.response.ApiResponse;
import com.evyoog.gl.reporting.segment.dto.PLBySegmentResponse;
import com.evyoog.gl.reporting.segment.service.PLBySegmentService;
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
@Tag(name = "GL-26 Segment Reporting")
public class PLBySegmentController {

    private final PLBySegmentService plBySegmentService;

    @GetMapping("/api/v1/gl/reports/pl-by-segment")
    @PreAuthorize("hasAuthority('gl:pl:view')")
    @Operation(summary = "Generate a Profit and Loss statement pivoted by a Finance Dimension segment "
            + "(e.g. Cost Centre or Product)")
    public ApiResponse<PLBySegmentResponse> getPLBySegment(
            @RequestParam UUID legalEntityId,
            @RequestParam UUID periodId,
            @RequestParam String segmentType,
            @RequestParam(required = false, defaultValue = "false") boolean includeZeroBalances) {
        return ApiResponse.ok(
                plBySegmentService.generate(legalEntityId, periodId, segmentType, includeZeroBalances));
    }
}
