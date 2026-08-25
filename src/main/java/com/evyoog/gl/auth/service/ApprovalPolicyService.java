package com.evyoog.gl.auth.service;

import com.evyoog.gl.auth.domain.ApprovalPolicy;
import com.evyoog.gl.auth.dto.ApprovalPolicyRequest;
import com.evyoog.gl.auth.dto.ApprovalPolicyResponse;
import com.evyoog.gl.auth.dto.UpdateApprovalPolicyRequest;
import com.evyoog.gl.auth.repository.ApprovalPolicyRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.domain.BusinessUnit;
import com.evyoog.gl.enterprise.domain.InventoryOrganisation;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.BusinessUnitRepository;
import com.evyoog.gl.enterprise.repository.InventoryOrganisationRepository;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves and manages the approval policy lookup chain:
 * Inventory Org → Business Unit → Legal Entity → (caller falls back to
 * journal_source.requires_approval if no policy row matches at any level).
 */
@Service
@RequiredArgsConstructor
public class ApprovalPolicyService {

    private final ApprovalPolicyRepository policyRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final BusinessUnitRepository businessUnitRepository;
    private final InventoryOrganisationRepository inventoryOrganisationRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Optional<ApprovalPolicy> resolvePolicy(UUID legalEntityId, UUID businessUnitId,
                                                    UUID inventoryOrgId, String journalSourceCode) {
        if (inventoryOrgId != null) {
            Optional<ApprovalPolicy> policy = policyRepository
                    .findActivePolicy(legalEntityId, businessUnitId, inventoryOrgId, journalSourceCode);
            if (policy.isPresent()) {
                return policy;
            }
        }

        if (businessUnitId != null) {
            Optional<ApprovalPolicy> policy = policyRepository
                    .findActivePolicy(legalEntityId, businessUnitId, null, journalSourceCode);
            if (policy.isPresent()) {
                return policy;
            }
        }

        return policyRepository.findActivePolicy(legalEntityId, null, null, journalSourceCode);
    }

    @Transactional
    public ApprovalPolicyResponse create(ApprovalPolicyRequest request, String performedBy) {
        LegalEntity legalEntity = legalEntityRepository.findById(request.legalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", request.legalEntityId()));
        BusinessUnit businessUnit = request.businessUnitId() != null
                ? businessUnitRepository.findById(request.businessUnitId())
                        .orElseThrow(() -> new ResourceNotFoundException("BusinessUnit", request.businessUnitId()))
                : null;
        InventoryOrganisation inventoryOrg = request.inventoryOrgId() != null
                ? inventoryOrganisationRepository.findById(request.inventoryOrgId())
                        .orElseThrow(() -> new ResourceNotFoundException("InventoryOrganisation", request.inventoryOrgId()))
                : null;

        policyRepository.findActivePolicy(request.legalEntityId(), request.businessUnitId(),
                        request.inventoryOrgId(), request.journalSourceCode())
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("DUPLICATE_APPROVAL_POLICY",
                            "An active approval policy already exists for this scope and journal source.", "journalSourceCode");
                });

        ApprovalPolicy policy = ApprovalPolicy.builder()
                .legalEntity(legalEntity)
                .businessUnit(businessUnit)
                .inventoryOrg(inventoryOrg)
                .journalSourceCode(request.journalSourceCode())
                .requiresApproval(request.requiresApproval())
                .approvalThresholdAmount(request.approvalThresholdAmount())
                .approverRoleCode(request.approverRoleCode())
                .createdBy(performedBy)
                .build();
        ApprovalPolicy saved = policyRepository.save(policy);

        ApprovalPolicyResponse response = toResponse(saved);
        auditService.log(AuditAction.CREATE, "auth_approval_policy", saved.getId(), null, response, performedBy);
        return response;
    }

    @Transactional
    public ApprovalPolicyResponse update(UUID id, ApprovalPolicyRequest request, String performedBy) {
        ApprovalPolicy policy = findOrThrow(id);
        ApprovalPolicyResponse before = toResponse(policy);

        policy.setRequiresApproval(request.requiresApproval());
        policy.setApprovalThresholdAmount(request.approvalThresholdAmount());
        policy.setApproverRoleCode(request.approverRoleCode());
        policy.setUpdatedBy(performedBy);

        ApprovalPolicy saved = policyRepository.save(policy);
        ApprovalPolicyResponse response = toResponse(saved);
        auditService.log(AuditAction.UPDATE, "auth_approval_policy", saved.getId(), before, response, performedBy);
        return response;
    }

    @Transactional
    public ApprovalPolicyResponse updatePolicy(UUID id, UpdateApprovalPolicyRequest request, String performedBy) {
        ApprovalPolicy policy = findOrThrow(id);
        ApprovalPolicyResponse before = toResponse(policy);

        if (request.requiresApproval() != null) {
            policy.setRequiresApproval(request.requiresApproval());
        }
        if (request.approvalThresholdAmount() != null) {
            policy.setApprovalThresholdAmount(request.approvalThresholdAmount());
        }
        if (request.approverRoleCode() != null) {
            policy.setApproverRoleCode(request.approverRoleCode());
        }
        if (request.isActive() != null) {
            policy.setActive(request.isActive());
        }
        policy.setUpdatedBy(performedBy);

        ApprovalPolicy saved = policyRepository.save(policy);
        ApprovalPolicyResponse response = toResponse(saved);
        auditService.log(AuditAction.UPDATE, "auth_approval_policy", saved.getId(), before, response, performedBy);
        return response;
    }

    @Transactional
    public void delete(UUID id, String performedBy) {
        ApprovalPolicy policy = findOrThrow(id);
        ApprovalPolicyResponse before = toResponse(policy);

        policy.setActive(false);
        policy.setUpdatedBy(performedBy);
        ApprovalPolicy saved = policyRepository.save(policy);

        auditService.log(AuditAction.DELETE, "auth_approval_policy", saved.getId(), before, toResponse(saved), performedBy);
    }

    @Transactional(readOnly = true)
    public List<ApprovalPolicyResponse> list(UUID legalEntityId) {
        return policyRepository.findByLegalEntityId(legalEntityId).stream().map(this::toResponse).toList();
    }

    private ApprovalPolicyResponse toResponse(ApprovalPolicy policy) {
        return ApprovalPolicyResponse.builder()
                .id(policy.getId())
                .legalEntityId(policy.getLegalEntity().getId())
                .businessUnitId(policy.getBusinessUnit() != null ? policy.getBusinessUnit().getId() : null)
                .inventoryOrgId(policy.getInventoryOrg() != null ? policy.getInventoryOrg().getId() : null)
                .journalSourceCode(policy.getJournalSourceCode())
                .requiresApproval(policy.isRequiresApproval())
                .approvalThresholdAmount(policy.getApprovalThresholdAmount())
                .approverRoleCode(policy.getApproverRoleCode())
                .isActive(policy.isActive())
                .updatedBy(policy.getUpdatedBy())
                .build();
    }

    private ApprovalPolicy findOrThrow(UUID id) {
        return policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalPolicy", id));
    }
}
