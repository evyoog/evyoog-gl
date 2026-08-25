package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.ApprovalPolicy;
import com.evyoog.gl.auth.dto.ApprovalPolicyResponse;
import com.evyoog.gl.auth.dto.UpdateApprovalPolicyRequest;
import com.evyoog.gl.auth.repository.ApprovalPolicyRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.BusinessUnitRepository;
import com.evyoog.gl.enterprise.repository.InventoryOrganisationRepository;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalPolicyServiceTest {

    @Mock private ApprovalPolicyRepository policyRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private BusinessUnitRepository businessUnitRepository;
    @Mock private InventoryOrganisationRepository inventoryOrganisationRepository;
    @Mock private AuditService auditService;

    @InjectMocks
    private ApprovalPolicyService service;

    private final UUID legalEntityId = UUID.randomUUID();
    private final UUID businessUnitId = UUID.randomUUID();
    private final UUID inventoryOrgId = UUID.randomUUID();
    private final String source = "AP";

    @Test
    void testResolvePolicy_invOrgLevel_winsOverBU() {
        ApprovalPolicy invOrgPolicy = ApprovalPolicy.builder().id(UUID.randomUUID()).build();
        when(policyRepository.findActivePolicy(legalEntityId, businessUnitId, inventoryOrgId, source))
                .thenReturn(Optional.of(invOrgPolicy));

        Optional<ApprovalPolicy> result = service.resolvePolicy(legalEntityId, businessUnitId, inventoryOrgId, source);

        assertThat(result).contains(invOrgPolicy);
        verify(policyRepository, never()).findActivePolicy(legalEntityId, businessUnitId, null, source);
        verify(policyRepository, never()).findActivePolicy(legalEntityId, null, null, source);
    }

    @Test
    void testResolvePolicy_buLevel_winsOverLE() {
        ApprovalPolicy buPolicy = ApprovalPolicy.builder().id(UUID.randomUUID()).build();
        when(policyRepository.findActivePolicy(legalEntityId, businessUnitId, null, source))
                .thenReturn(Optional.of(buPolicy));

        Optional<ApprovalPolicy> result = service.resolvePolicy(legalEntityId, businessUnitId, null, source);

        assertThat(result).contains(buPolicy);
        verify(policyRepository, never()).findActivePolicy(legalEntityId, null, null, source);
    }

    @Test
    void testResolvePolicy_leLevel_fallback() {
        ApprovalPolicy lePolicy = ApprovalPolicy.builder().id(UUID.randomUUID()).build();
        when(policyRepository.findActivePolicy(legalEntityId, businessUnitId, inventoryOrgId, source))
                .thenReturn(Optional.empty());
        when(policyRepository.findActivePolicy(legalEntityId, businessUnitId, null, source))
                .thenReturn(Optional.empty());
        when(policyRepository.findActivePolicy(legalEntityId, null, null, source))
                .thenReturn(Optional.of(lePolicy));

        Optional<ApprovalPolicy> result = service.resolvePolicy(legalEntityId, businessUnitId, inventoryOrgId, source);

        assertThat(result).contains(lePolicy);
    }

    @Test
    void testResolvePolicy_noPolicyFound_returnsEmpty() {
        when(policyRepository.findActivePolicy(legalEntityId, businessUnitId, inventoryOrgId, source))
                .thenReturn(Optional.empty());
        when(policyRepository.findActivePolicy(legalEntityId, businessUnitId, null, source))
                .thenReturn(Optional.empty());
        when(policyRepository.findActivePolicy(legalEntityId, null, null, source))
                .thenReturn(Optional.empty());

        Optional<ApprovalPolicy> result = service.resolvePolicy(legalEntityId, businessUnitId, inventoryOrgId, source);

        assertThat(result).isEmpty();
    }

    @Test
    void testUpdateApprovalPolicy_populatesUpdatedBy() {
        UUID policyId = UUID.randomUUID();
        LegalEntity legalEntity = LegalEntity.builder().id(legalEntityId).build();
        ApprovalPolicy policy = ApprovalPolicy.builder()
                .id(policyId)
                .legalEntity(legalEntity)
                .journalSourceCode(source)
                .requiresApproval(true)
                .approvalThresholdAmount(BigDecimal.valueOf(1000))
                .build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));
        when(policyRepository.save(any(ApprovalPolicy.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateApprovalPolicyRequest request = new UpdateApprovalPolicyRequest(
                null, BigDecimal.valueOf(5000), "GL_APPROVER", null);

        ApprovalPolicyResponse response = service.updatePolicy(policyId, request, "admin@evyoog.com");

        assertThat(response.approvalThresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(response.approverRoleCode()).isEqualTo("GL_APPROVER");
        assertThat(response.updatedBy()).isEqualTo("admin@evyoog.com");
        assertThat(policy.getUpdatedBy()).isEqualTo("admin@evyoog.com");
    }
}
