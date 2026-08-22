package com.evyoog.gl.dimension.service;

import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.dto.CreateFinanceDimensionRequest;
import com.evyoog.gl.dimension.dto.FinanceDimensionResponse;
import com.evyoog.gl.dimension.dto.UpdateFinanceDimensionRequest;
import com.evyoog.gl.dimension.mapper.FinanceDimensionMapper;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FinanceDimensionService {

    private static final int MAX_DIMENSIONS = 15;
    private static final int THIN_MODE_DIMENSION_LIMIT = 2;

    private final FinanceDimensionRepository repository;
    private final LedgerRepository ledgerRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final FinanceDimensionMapper mapper;
    private final AuditService auditService;

    @Transactional
    public FinanceDimensionResponse create(CreateFinanceDimensionRequest request, String performedBy) {
        Ledger ledger = ledgerRepository.findById(request.ledgerId())
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", request.ledgerId()));

        if (repository.existsByLedgerIdAndCode(ledger.getId(), request.code())) {
            throw new DuplicateResourceException("DUPLICATE_DIMENSION_CODE",
                    "A Finance Dimension with code '" + request.code() + "' already exists for this Ledger.", "code");
        }

        long activeCount = repository.countByLedgerIdAndIsActiveTrue(ledger.getId());
        if (activeCount >= MAX_DIMENSIONS) {
            throw new EvyoogException("MAX_DIMENSIONS_EXCEEDED",
                    "A Ledger may have a maximum of " + MAX_DIMENSIONS + " Finance Dimensions.");
        }

        if (ledger.getFinanceMode() == FinanceMode.THIN) {
            if (activeCount >= THIN_MODE_DIMENSION_LIMIT) {
                throw new EvyoogException("THIN_DIMENSION_LIMIT",
                        "Thin mode Ledgers support exactly two Finance Dimensions: " +
                                "LEGAL_ENTITY and NATURAL_ACCOUNT. No additional dimensions permitted.");
            }
            if (request.dimensionType() != DimensionType.LEGAL_ENTITY
                    && request.dimensionType() != DimensionType.NATURAL_ACCOUNT) {
                throw new EvyoogException("THIN_DIMENSION_TYPE_INVALID",
                        "Thin mode Ledgers only support LEGAL_ENTITY and NATURAL_ACCOUNT dimension types.");
            }
        }

        if (request.dimensionType() == DimensionType.LEGAL_ENTITY
                && repository.existsByLedgerIdAndDimensionTypeAndIsActiveTrue(ledger.getId(), DimensionType.LEGAL_ENTITY)) {
            throw new EvyoogException("LEGAL_ENTITY_DIMENSION_EXISTS",
                    "A Ledger may have exactly one LEGAL_ENTITY Finance Dimension.");
        }

        if (request.dimensionType() == DimensionType.NATURAL_ACCOUNT
                && repository.existsByLedgerIdAndDimensionTypeAndIsActiveTrue(ledger.getId(), DimensionType.NATURAL_ACCOUNT)) {
            throw new EvyoogException("NATURAL_ACCOUNT_DIMENSION_EXISTS",
                    "A Ledger may have exactly one NATURAL_ACCOUNT Finance Dimension.");
        }

        boolean isBalancing = request.isBalancing() != null && request.isBalancing();
        validateBalancingConfig(isBalancing, request.balancingSequence());
        UUID coaStructureId = ledger.getCoaStructure() != null ? ledger.getCoaStructure().getId() : null;
        if (isBalancing && coaStructureId != null
                && repository.existsByCoaStructureIdAndIsBalancingTrueAndBalancingSequence(
                        coaStructureId, request.balancingSequence())) {
            throw new EvyoogException("DUPLICATE_BALANCING_SEQUENCE",
                    "A balancing dimension with sequence " + request.balancingSequence() +
                            " already exists for this COA Structure.");
        }

        FinanceDimension entity = mapper.toEntity(request);
        entity.setLedger(ledger);
        entity.setRequired(request.isRequired() != null && request.isRequired());
        entity.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : 0);
        entity.setBalancing(isBalancing);
        entity.setBalancingSequence(request.balancingSequence());
        entity.setCreatedBy(performedBy);
        entity.setUpdatedBy(performedBy);

        FinanceDimension saved = repository.saveAndFlush(entity);
        FinanceDimensionResponse response = mapper.toResponse(saved, 0L);
        auditService.log(AuditAction.CREATE, "finance_dimension", saved.getId(), null, response, performedBy);

        return response;
    }

    @Transactional
    public FinanceDimensionResponse update(UUID id, UpdateFinanceDimensionRequest request, String performedBy) {
        FinanceDimension entity = findOrThrow(id);
        FinanceDimensionResponse before = mapper.toResponse(entity, valueCountFor(entity.getId()));

        mapper.updateFromRequest(request, entity);
        if (request.isRequired() != null) {
            entity.setRequired(request.isRequired());
        }
        if (request.isBalancing() != null || request.balancingSequence() != null) {
            boolean effectiveIsBalancing = request.isBalancing() != null ? request.isBalancing() : entity.isBalancing();
            Integer effectiveSequence = effectiveIsBalancing
                    ? (request.balancingSequence() != null ? request.balancingSequence() : entity.getBalancingSequence())
                    : null;
            validateBalancingConfig(effectiveIsBalancing, effectiveSequence);

            UUID coaStructureId = entity.getCoaStructure() != null ? entity.getCoaStructure().getId() : null;
            if (effectiveIsBalancing && coaStructureId != null
                    && repository.existsByCoaStructureIdAndIsBalancingTrueAndBalancingSequenceAndIdNot(
                            coaStructureId, effectiveSequence, entity.getId())) {
                throw new EvyoogException("DUPLICATE_BALANCING_SEQUENCE",
                        "A balancing dimension with sequence " + effectiveSequence +
                                " already exists for this COA Structure.");
            }

            entity.setBalancing(effectiveIsBalancing);
            entity.setBalancingSequence(effectiveSequence);
        }
        entity.setUpdatedBy(performedBy);

        FinanceDimension saved = repository.saveAndFlush(entity);
        FinanceDimensionResponse response = mapper.toResponse(saved, valueCountFor(saved.getId()));
        auditService.log(AuditAction.UPDATE, "finance_dimension", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional
    public void deactivate(UUID id, String performedBy) {
        FinanceDimension entity = findOrThrow(id);
        FinanceDimensionResponse before = mapper.toResponse(entity, valueCountFor(entity.getId()));

        // Phase 1: GL-11 (Manual Journal Entry) does not exist yet, so there is no
        // journal history that references this dimension's values. Once posting
        // exists, guard here against deactivating a Finance Dimension in use.
        entity.setActive(false);
        entity.setUpdatedBy(performedBy);

        FinanceDimension saved = repository.saveAndFlush(entity);
        FinanceDimensionResponse response = mapper.toResponse(saved, valueCountFor(saved.getId()));
        auditService.log(AuditAction.DELETE, "finance_dimension", saved.getId(), before, response, performedBy);
    }

    @Transactional(readOnly = true)
    public FinanceDimensionResponse getById(UUID id) {
        FinanceDimension entity = findOrThrow(id);
        return mapper.toResponse(entity, valueCountFor(entity.getId()));
    }

    @Transactional(readOnly = true)
    public List<FinanceDimensionResponse> list(UUID ledgerId, UUID coaStructureId, DimensionType dimensionType) {
        List<FinanceDimension> entities;
        if (coaStructureId != null) {
            entities = repository.findByCoaStructureId(coaStructureId);
        } else if (ledgerId == null) {
            entities = repository.findAll();
        } else if (dimensionType != null) {
            entities = repository.findByLedgerIdAndDimensionType(ledgerId, dimensionType);
        } else {
            entities = repository.findByLedgerId(ledgerId);
        }
        if (coaStructureId != null && dimensionType != null) {
            entities = entities.stream().filter(fd -> fd.getDimensionType() == dimensionType).toList();
        }
        return entities.stream()
                .map(entity -> mapper.toResponse(entity, valueCountFor(entity.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FinanceDimensionResponse> getBalancingDimensions(UUID coaStructureId) {
        return repository.findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(coaStructureId).stream()
                .map(entity -> mapper.toResponse(entity, valueCountFor(entity.getId())))
                .toList();
    }

    private void validateBalancingConfig(boolean isBalancing, Integer balancingSequence) {
        if (isBalancing && (balancingSequence == null || !List.of(2, 3).contains(balancingSequence))) {
            throw new EvyoogException("INVALID_BALANCING_SEQUENCE",
                    "balancingSequence must be 2 (secondary) or 3 (tertiary) when isBalancing is true.");
        }
        if (!isBalancing && balancingSequence != null) {
            throw new EvyoogException("INVALID_BALANCING_CONFIG",
                    "balancingSequence must be null when isBalancing is false.");
        }
    }

    private long valueCountFor(UUID financeDimensionId) {
        return dimensionValueRepository.countByFinanceDimensionIdAndIsActiveTrue(financeDimensionId);
    }

    private FinanceDimension findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FinanceDimension", id));
    }
}
