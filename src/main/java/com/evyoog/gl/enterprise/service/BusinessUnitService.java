package com.evyoog.gl.enterprise.service;

import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.common.exception.ValidationException;
import com.evyoog.gl.enterprise.domain.BusinessUnit;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.dto.BusinessUnitResponse;
import com.evyoog.gl.enterprise.dto.CreateBusinessUnitRequest;
import com.evyoog.gl.enterprise.dto.UpdateBusinessUnitRequest;
import com.evyoog.gl.enterprise.mapper.BusinessUnitMapper;
import com.evyoog.gl.enterprise.repository.BusinessUnitRepository;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BusinessUnitService {

    private static final Pattern GSTIN_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");

    private final BusinessUnitRepository repository;
    private final LegalEntityRepository legalEntityRepository;
    private final BusinessUnitMapper mapper;
    private final AuditService auditService;

    @Transactional
    public BusinessUnitResponse create(CreateBusinessUnitRequest request, String performedBy) {
        LegalEntity legalEntity = legalEntityRepository.findById(request.legalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", request.legalEntityId()));

        validateGstin(request.gstin());
        if (StringUtils.hasText(request.gstin())
                && repository.existsByGstinAndLegalEntityIdNot(request.gstin(), legalEntity.getId())) {
            throw new DuplicateResourceException(
                    "DUPLICATE_GSTIN", "A business unit with GSTIN '" + request.gstin() + "' already exists under a different Legal Entity.", "gstin");
        }
        if (repository.existsByLegalEntityIdAndCode(legalEntity.getId(), request.code())) {
            throw new DuplicateResourceException(
                    "DUPLICATE_CODE", "A business unit with code '" + request.code() + "' already exists in this legal entity.", "code");
        }

        BusinessUnit entity = mapper.toEntity(request);
        entity.setLegalEntity(legalEntity);
        entity.setStateCode(resolveStateCode(request.gstin(), request.stateCode()));
        entity.setCreatedBy(performedBy);
        entity.setUpdatedBy(performedBy);

        BusinessUnit saved = repository.saveAndFlush(entity);
        BusinessUnitResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.CREATE, "business_unit", saved.getId(), null, response, performedBy);

        return response;
    }

    @Transactional
    public BusinessUnitResponse update(UUID id, UpdateBusinessUnitRequest request, String performedBy) {
        BusinessUnit entity = findOrThrow(id);
        BusinessUnitResponse before = mapper.toResponse(entity);

        if (StringUtils.hasText(request.gstin()) && !request.gstin().equals(entity.getGstin())) {
            validateGstin(request.gstin());
            if (repository.existsByGstinAndLegalEntityIdNot(request.gstin(), entity.getLegalEntity().getId())) {
                throw new DuplicateResourceException(
                        "DUPLICATE_GSTIN", "A business unit with GSTIN '" + request.gstin() + "' already exists under a different Legal Entity.", "gstin");
            }
        }

        mapper.updateFromRequest(request, entity);
        entity.setUpdatedBy(performedBy);

        BusinessUnit saved = repository.saveAndFlush(entity);
        BusinessUnitResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.UPDATE, "business_unit", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional(readOnly = true)
    public BusinessUnitResponse getById(UUID id) {
        return mapper.toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<BusinessUnitResponse> list(UUID legalEntityId) {
        List<BusinessUnit> entities = legalEntityId != null
                ? repository.findByLegalEntityId(legalEntityId)
                : repository.findAll();
        return entities.stream().map(mapper::toResponse).toList();
    }

    private void validateGstin(String gstin) {
        if (StringUtils.hasText(gstin) && !GSTIN_PATTERN.matcher(gstin).matches()) {
            throw new ValidationException("INVALID_GSTIN",
                    "GSTIN must be a valid 15-character Indian GST number.", "gstin");
        }
    }

    private String resolveStateCode(String gstin, String requestedStateCode) {
        if (StringUtils.hasText(requestedStateCode)) {
            return requestedStateCode;
        }
        return StringUtils.hasText(gstin) ? gstin.substring(0, 2) : null;
    }

    private BusinessUnit findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessUnit", id));
    }
}
