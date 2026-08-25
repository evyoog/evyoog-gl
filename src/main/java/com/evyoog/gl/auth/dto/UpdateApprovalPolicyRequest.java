package com.evyoog.gl.auth.dto;

import java.math.BigDecimal;

public record UpdateApprovalPolicyRequest(

        Boolean requiresApproval,

        BigDecimal approvalThresholdAmount,

        String approverRoleCode,

        Boolean isActive
) {
}
