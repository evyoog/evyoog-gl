package com.evyoog.gl.ledger.service;

import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.domain.AccountingStandard;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.domain.LedgerCategory;
import com.evyoog.gl.ledger.dto.CreateLedgerRequest;
import com.evyoog.gl.ledger.dto.LedgerResponse;
import com.evyoog.gl.ledger.dto.UpdateDynamicInsertRequest;
import com.evyoog.gl.ledger.dto.UpdateFinanceModeRequest;
import com.evyoog.gl.ledger.dto.UpdateLedgerRequest;
import com.evyoog.gl.ledger.mapper.LedgerMapper;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final Map<FinanceMode, Integer> FINANCE_MODE_RANK = Map.of(
            FinanceMode.EVENT_ONLY, 0,
            FinanceMode.THIN, 1,
            FinanceMode.THICK, 2
    );

    private final LedgerRepository repository;
    private final LedgerMapper mapper;
    private final AuditService auditService;

    @Transactional
    public LedgerResponse create(CreateLedgerRequest request, String performedBy) {
        if (repository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                    "DUPLICATE_CODE", "A ledger with code '" + request.code() + "' already exists.", "code");
        }

        Ledger entity = mapper.toEntity(request);
        entity.setLedgerCategory(request.ledgerCategory() != null ? request.ledgerCategory() : LedgerCategory.PRIMARY);
        entity.setFunctionalCurrency(request.functionalCurrency() != null ? request.functionalCurrency() : "INR");
        entity.setAccountingStandard(request.accountingStandard() != null ? request.accountingStandard() : AccountingStandard.IND_AS);
        entity.setCreatedBy(performedBy);
        entity.setUpdatedBy(performedBy);

        Ledger saved = repository.saveAndFlush(entity);
        LedgerResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.CREATE, "ledger", saved.getId(), null, response, performedBy);

        return response;
    }

    @Transactional
    public LedgerResponse update(UUID id, UpdateLedgerRequest request, String performedBy) {
        Ledger entity = findOrThrow(id);
        LedgerResponse before = mapper.toResponse(entity);

        mapper.updateFromRequest(request, entity);
        entity.setUpdatedBy(performedBy);

        Ledger saved = repository.saveAndFlush(entity);
        LedgerResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.UPDATE, "ledger", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional
    public LedgerResponse upgradeFinanceMode(UUID id, UpdateFinanceModeRequest request, String performedBy) {
        Ledger entity = findOrThrow(id);
        LedgerResponse before = mapper.toResponse(entity);

        FinanceMode current = entity.getFinanceMode();
        FinanceMode requested = request.financeMode();
        if (FINANCE_MODE_RANK.get(requested) <= FINANCE_MODE_RANK.get(current)) {
            throw new EvyoogException("FINANCE_MODE_DOWNGRADE",
                    "Finance mode can only be upgraded (EVENT_ONLY -> THIN -> THICK). " +
                            "Downgrading is not permitted as it would invalidate existing journal history.",
                    HttpStatus.CONFLICT, "financeMode");
        }

        entity.setFinanceMode(requested);
        entity.setUpdatedBy(performedBy);

        Ledger saved = repository.saveAndFlush(entity);
        LedgerResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.UPDATE, "ledger", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional
    public LedgerResponse updateDynamicInsert(UUID id, UpdateDynamicInsertRequest request, String performedBy) {
        Ledger entity = findOrThrow(id);
        LedgerResponse before = mapper.toResponse(entity);

        entity.setAllowDynamicInsert(request.allowDynamicInsert());
        entity.setUpdatedBy(performedBy);

        Ledger saved = repository.saveAndFlush(entity);
        LedgerResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.UPDATE, "ledger", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional
    public void deactivate(UUID id, String performedBy) {
        Ledger entity = findOrThrow(id);
        LedgerResponse before = mapper.toResponse(entity);

        // Phase 1: GL-11 (Manual Journal Entry) does not exist yet, so there is no
        // journal history to protect. Once posting exists, guard here against
        // deactivating a Ledger with posted journals.
        entity.setActive(false);
        entity.setUpdatedBy(performedBy);

        Ledger saved = repository.saveAndFlush(entity);
        auditService.log(AuditAction.DELETE, "ledger", saved.getId(), before, mapper.toResponse(saved), performedBy);
    }

    @Transactional(readOnly = true)
    public LedgerResponse getById(UUID id) {
        return mapper.toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<LedgerResponse> list(UUID businessGroupId) {
        List<Ledger> entities = businessGroupId != null
                ? repository.findByBusinessGroupId(businessGroupId)
                : repository.findAll();
        return entities.stream().map(mapper::toResponse).toList();
    }

    private Ledger findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", id));
    }
}
