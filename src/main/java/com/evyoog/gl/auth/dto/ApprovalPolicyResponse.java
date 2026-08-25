package com.evyoog.gl.auth.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
public record ApprovalPolicyResponse(
        UUID id,
        UUID legalEntityId,
        UUID businessUnitId,
        UUID inventoryOrgId,
        String journalSourceCode,
        boolean requiresApproval,
        BigDecimal approvalThresholdAmount,
        String approverRoleCode,
        boolean isActive,
        String updatedBy
) {
}
