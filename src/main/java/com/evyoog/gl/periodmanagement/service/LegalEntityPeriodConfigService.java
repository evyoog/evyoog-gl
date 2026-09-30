package com.evyoog.gl.periodmanagement.service;

import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.periodmanagement.domain.LegalEntityPeriodConfig;
import com.evyoog.gl.periodmanagement.dto.LegalEntityPeriodConfigResponse;
import com.evyoog.gl.periodmanagement.dto.UpdateLegalEntityPeriodConfigRequest;
import com.evyoog.gl.periodmanagement.repository.LegalEntityPeriodConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * GET/PUT CRUD for the per-Legal-Entity period-management config row. Also
 * the {@link #getOrDefault(UUID)} accessor used internally by
 * {@code PeriodManagementService} for rule evaluation — a Legal Entity with
 * no config row (e.g. one created after the V33 migration seeded existing
 * rows, or in a fresh Testcontainers database) falls back to the same
 * defaults V33 seeded (max 2 open, 1 future period, adjustment enabled)
 * rather than failing.
 */
@Service
@RequiredArgsConstructor
public class LegalEntityPeriodConfigService {

    private static final int DEFAULT_MAX_OPEN_PERIODS = 2;
    private static final int DEFAULT_FUTURE_PERIOD_LIMIT = 1;
    private static final boolean DEFAULT_ADJUSTMENT_ENABLED = true;

    private final LegalEntityPeriodConfigRepository repository;
    private final LegalEntityRepository legalEntityRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public LegalEntityPeriodConfigResponse getByLegalEntityId(UUID legalEntityId) {
        if (!legalEntityRepository.existsById(legalEntityId)) {
            throw new ResourceNotFoundException("LegalEntity", legalEntityId);
        }
        return repository.findByLegalEntityId(legalEntityId)
                .map(this::toResponse)
                .orElseGet(() -> defaultResponse(legalEntityId));
    }

    /** Used by {@code PeriodManagementService} — never throws, always returns usable defaults. */
    @Transactional(readOnly = true)
    public LegalEntityPeriodConfig getOrDefault(UUID legalEntityId) {
        return repository.findByLegalEntityId(legalEntityId)
                .orElseGet(() -> LegalEntityPeriodConfig.builder()
                        .maxOpenPeriods(DEFAULT_MAX_OPEN_PERIODS)
                        .futurePeriodLimit(DEFAULT_FUTURE_PERIOD_LIMIT)
                        .adjustmentPeriodEnabled(DEFAULT_ADJUSTMENT_ENABLED)
                        .build());
    }

    @Transactional
    public LegalEntityPeriodConfigResponse update(UUID legalEntityId, UpdateLegalEntityPeriodConfigRequest request,
                                                    String performedBy) {
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", legalEntityId));

        LegalEntityPeriodConfig entity = repository.findByLegalEntityId(legalEntityId).orElse(null);
        LegalEntityPeriodConfigResponse before = entity != null ? toResponse(entity) : null;
        AuditAction action = entity != null ? AuditAction.UPDATE : AuditAction.CREATE;

        if (entity == null) {
            entity = LegalEntityPeriodConfig.builder()
                    .legalEntity(legalEntity)
                    .createdBy(performedBy)
                    .build();
        }
        entity.setMaxOpenPeriods(request.maxOpenPeriods());
        entity.setFuturePeriodLimit(request.futurePeriodLimit());
        entity.setAdjustmentPeriodEnabled(request.adjustmentPeriodEnabled());
        entity.setUpdatedBy(performedBy);

        LegalEntityPeriodConfig saved = repository.saveAndFlush(entity);
        LegalEntityPeriodConfigResponse response = toResponse(saved);
        auditService.log(action, "legal_entity_period_config", saved.getId(), before, response, performedBy);
        return response;
    }

    private LegalEntityPeriodConfigResponse defaultResponse(UUID legalEntityId) {
        return new LegalEntityPeriodConfigResponse(null, legalEntityId, DEFAULT_MAX_OPEN_PERIODS,
                DEFAULT_FUTURE_PERIOD_LIMIT, DEFAULT_ADJUSTMENT_ENABLED, null, null, null, null);
    }

    private LegalEntityPeriodConfigResponse toResponse(LegalEntityPeriodConfig entity) {
        return new LegalEntityPeriodConfigResponse(
                entity.getId(),
                entity.getLegalEntity().getId(),
                entity.getMaxOpenPeriods(),
                entity.getFuturePeriodLimit(),
                entity.getAdjustmentPeriodEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy());
    }
}
