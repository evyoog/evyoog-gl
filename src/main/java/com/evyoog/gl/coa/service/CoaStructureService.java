package com.evyoog.gl.coa.service;

import com.evyoog.gl.coa.domain.CoaStructure;
import com.evyoog.gl.coa.dto.CoaSegmentSummary;
import com.evyoog.gl.coa.dto.CoaStructureResponse;
import com.evyoog.gl.coa.dto.CreateCoaSegmentRequest;
import com.evyoog.gl.coa.dto.CreateCoaStructureRequest;
import com.evyoog.gl.coa.dto.UpdateCoaStructureRequest;
import com.evyoog.gl.coa.mapper.CoaStructureMapper;
import com.evyoog.gl.coa.repository.CoaStructureRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.enterprise.domain.BusinessGroup;
import com.evyoog.gl.enterprise.repository.BusinessGroupRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CoaStructureService {

    private final CoaStructureRepository repository;
    private final BusinessGroupRepository businessGroupRepository;
    private final LedgerRepository ledgerRepository;
    private final FinanceDimensionRepository financeDimensionRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final CoaStructureMapper mapper;
    private final AuditService auditService;

    @Transactional
    public CoaStructureResponse create(CreateCoaStructureRequest request, String performedBy) {
        BusinessGroup businessGroup = businessGroupRepository.findById(request.businessGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("BusinessGroup", request.businessGroupId()));

        if (repository.existsByBusinessGroupIdAndCode(businessGroup.getId(), request.code())) {
            throw new DuplicateResourceException("DUPLICATE_COA_STRUCTURE_CODE",
                    "A COA Structure with code '" + request.code() + "' already exists for this Business Group.",
                    "code");
        }

        Set<String> seenCodes = new HashSet<>();
        for (CreateCoaSegmentRequest segmentRequest : request.segments()) {
            if (!seenCodes.add(segmentRequest.code())) {
                throw new EvyoogException("DUPLICATE_SEGMENT_CODE",
                        "Segment code '" + segmentRequest.code() + "' is duplicated in the request.",
                        HttpStatus.BAD_REQUEST, "segments");
            }
        }

        CoaStructure structure = CoaStructure.builder()
                .businessGroup(businessGroup)
                .code(request.code())
                .name(request.name())
                .description(request.description())
                .separator(request.separator() != null && !request.separator().isBlank()
                        ? request.separator() : ".")
                .createdBy(performedBy)
                .updatedBy(performedBy)
                .build();
        CoaStructure savedStructure = repository.saveAndFlush(structure);

        for (CreateCoaSegmentRequest segmentRequest : request.segments()) {
            FinanceDimension segment = mapper.toSegmentEntity(segmentRequest);
            segment.setCoaStructure(savedStructure);
            segment.setDisplayOrder(segmentRequest.segmentNumber());
            segment.setRequired(Boolean.TRUE.equals(segmentRequest.isRequired()));
            segment.setCreatedBy(performedBy);
            segment.setUpdatedBy(performedBy);
            financeDimensionRepository.saveAndFlush(segment);
        }

        CoaStructureResponse response = buildResponse(savedStructure);
        auditService.log(AuditAction.CREATE, "coa_structure", savedStructure.getId(), null, response, performedBy);

        return response;
    }

    @Transactional
    public CoaStructureResponse update(UUID id, UpdateCoaStructureRequest request, String performedBy) {
        CoaStructure structure = findOrThrow(id);
        CoaStructureResponse before = buildResponse(structure);

        if (request.name() != null) {
            structure.setName(request.name());
        }
        if (request.description() != null) {
            structure.setDescription(request.description());
        }
        if (request.isActive() != null) {
            structure.setActive(request.isActive());
        }
        structure.setUpdatedBy(performedBy);

        CoaStructure saved = repository.saveAndFlush(structure);
        CoaStructureResponse response = buildResponse(saved);
        auditService.log(AuditAction.UPDATE, "coa_structure", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional
    public CoaStructureResponse addSegment(UUID coaStructureId, CreateCoaSegmentRequest request, String performedBy) {
        CoaStructure structure = findOrThrow(coaStructureId);

        if (financeDimensionRepository.existsByCoaStructureIdAndCode(structure.getId(), request.code())) {
            throw new DuplicateResourceException("DUPLICATE_SEGMENT_CODE",
                    "Segment code '" + request.code() + "' already exists in this COA Structure.", "code");
        }

        FinanceDimension segment = mapper.toSegmentEntity(request);
        segment.setCoaStructure(structure);
        segment.setDisplayOrder(request.segmentNumber());
        segment.setRequired(Boolean.TRUE.equals(request.isRequired()));

        // Keep the ledger_id backward-compat pointer in sync only when the
        // structure is unambiguously assigned to a single Ledger — see
        // assignToLedger() for why this pointer cannot represent more than one.
        List<Ledger> assignedLedgers = ledgerRepository.findByCoaStructureIdAndIsActiveTrue(structure.getId());
        if (assignedLedgers.size() == 1) {
            segment.setLedger(assignedLedgers.get(0));
        }

        segment.setCreatedBy(performedBy);
        segment.setUpdatedBy(performedBy);
        FinanceDimension saved = financeDimensionRepository.saveAndFlush(segment);

        CoaStructureResponse response = buildResponse(structure);
        auditService.log(AuditAction.CREATE, "finance_dimension", saved.getId(), null,
                mapper.toSegmentSummary(saved, 0L), performedBy);

        return response;
    }

    @Transactional
    public CoaStructureResponse removeSegment(UUID coaStructureId, UUID financeDimensionId, String performedBy) {
        CoaStructure structure = findOrThrow(coaStructureId);
        FinanceDimension segment = financeDimensionRepository.findById(financeDimensionId)
                .orElseThrow(() -> new ResourceNotFoundException("FinanceDimension", financeDimensionId));

        if (segment.getCoaStructure() == null || !segment.getCoaStructure().getId().equals(coaStructureId)) {
            throw new EvyoogException("SEGMENT_NOT_IN_STRUCTURE",
                    "Finance Dimension " + financeDimensionId + " does not belong to COA Structure " + coaStructureId,
                    HttpStatus.BAD_REQUEST);
        }

        CoaSegmentSummary before = mapper.toSegmentSummary(segment,
                dimensionValueRepository.countByFinanceDimensionIdAndIsActiveTrue(segment.getId()));

        segment.setActive(false);
        segment.setUpdatedBy(performedBy);
        FinanceDimension saved = financeDimensionRepository.saveAndFlush(segment);

        auditService.log(AuditAction.DELETE, "finance_dimension", saved.getId(), before,
                mapper.toSegmentSummary(saved, 0L), performedBy);

        return buildResponse(structure);
    }

    @Transactional
    public CoaStructureResponse assignToLedger(UUID coaStructureId, UUID ledgerId, String performedBy) {
        CoaStructure structure = findOrThrow(coaStructureId);
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", ledgerId));

        ledger.setCoaStructure(structure);
        ledger.setUpdatedBy(performedBy);
        Ledger savedLedger = ledgerRepository.saveAndFlush(ledger);

        // Backward-compat pointer: finance_dimension.ledger_id lets existing
        // ledger-scoped queries (e.g. PostingEngine) keep working unmodified.
        // It is a single scalar, so when a COA Structure is shared across more
        // than one Ledger only the most-recently-assigned Ledger's rows are
        // correct here — callers needing the shared segment definitions across
        // every assigned Ledger must go through coaStructureId, not ledgerId.
        List<FinanceDimension> segments = financeDimensionRepository.findByCoaStructureId(structure.getId());
        for (FinanceDimension segment : segments) {
            segment.setLedger(savedLedger);
            segment.setUpdatedBy(performedBy);
        }
        financeDimensionRepository.saveAll(segments);

        CoaStructureResponse response = buildResponse(structure);
        auditService.log(AuditAction.UPDATE, "ledger", savedLedger.getId(), null, response, performedBy);

        return response;
    }

    @Transactional(readOnly = true)
    public CoaStructureResponse getById(UUID id) {
        return buildResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<CoaStructureResponse> listByBusinessGroup(UUID businessGroupId) {
        return repository.findByBusinessGroupIdAndIsActiveTrue(businessGroupId).stream()
                .map(this::buildResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CoaStructureResponse getByLedgerId(UUID ledgerId) {
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", ledgerId));

        if (ledger.getCoaStructure() == null) {
            throw new EvyoogException("NO_COA_STRUCTURE_ASSIGNED",
                    "No COA Structure is assigned to this Ledger.", HttpStatus.NOT_FOUND);
        }

        return buildResponse(ledger.getCoaStructure());
    }

    @Transactional(readOnly = true)
    public String getCombinationFormat(UUID coaStructureId) {
        CoaStructure structure = findOrThrow(coaStructureId);
        List<FinanceDimension> segments = financeDimensionRepository
                .findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(structure.getId());

        return segments.stream()
                .map(segment -> "[" + segment.getCode() + "]")
                .collect(Collectors.joining(structure.getSeparator()));
    }

    private CoaStructureResponse buildResponse(CoaStructure structure) {
        List<CoaSegmentSummary> segments = financeDimensionRepository
                .findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(structure.getId()).stream()
                .map(segment -> mapper.toSegmentSummary(segment,
                        dimensionValueRepository.countByFinanceDimensionIdAndIsActiveTrue(segment.getId())))
                .toList();
        long assignedLedgerCount = ledgerRepository.countByCoaStructureIdAndIsActiveTrue(structure.getId());

        return new CoaStructureResponse(
                structure.getId(),
                structure.getBusinessGroup().getId(),
                structure.getCode(),
                structure.getName(),
                structure.getDescription(),
                structure.getSeparator(),
                segments.size(),
                segments,
                assignedLedgerCount,
                structure.isActive(),
                structure.getCreatedAt(),
                structure.getUpdatedAt(),
                structure.getCreatedBy());
    }

    private CoaStructure findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CoaStructure", id));
    }
}
