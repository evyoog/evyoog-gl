package com.evyoog.gl.combination.service;

import com.evyoog.gl.combination.domain.AccountCombination;
import com.evyoog.gl.combination.dto.AccountCombinationResponse;
import com.evyoog.gl.combination.dto.CreateAccountCombinationRequest;
import com.evyoog.gl.combination.dto.UpdateAccountCombinationRequest;
import com.evyoog.gl.combination.mapper.AccountCombinationMapper;
import com.evyoog.gl.combination.repository.AccountCombinationRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountCombinationService {

    private final AccountCombinationRepository repository;
    private final LedgerRepository ledgerRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final AccountCombinationMapper mapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // Called by the Posting Engine (Rule 10) for every non-empty line
    // combination. Auto-registers on first use when allowDynamicInsert is
    // TRUE; otherwise an unknown combination blocks posting.
    @Transactional
    public AccountCombination validate(UUID ledgerId, UUID legalEntityId, Map<String, String> combination,
                                        boolean allowDynamicInsert, String performedBy) {
        AccountCombination existing = repository
                .findByLedgerIdAndLegalEntityIdAndCombination(ledgerId, legalEntityId, toJson(combination))
                .orElse(null);

        if (existing != null) {
            if (!existing.isActive()) {
                throw new EvyoogException("COMBINATION_INACTIVE",
                        "Account combination " + combination + " is inactive and cannot be posted to.");
            }
            if (existing.getFirstUsedAt() == null) {
                existing.setFirstUsedAt(Instant.now());
                repository.save(existing);
            }
            return existing;
        }

        if (!allowDynamicInsert) {
            throw new EvyoogException("INVALID_ACCOUNT_COMBINATION",
                    "Account combination " + combination + " is not approved for this ledger. " +
                            "Please pre-approve the combination or enable Dynamic Insert.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        return autoRegister(ledgerId, legalEntityId, combination, performedBy);
    }

    private AccountCombination autoRegister(UUID ledgerId, UUID legalEntityId, Map<String, String> combination,
                                             String performedBy) {
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", ledgerId));
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", legalEntityId));

        AccountCombination entity = AccountCombination.builder()
                .ledger(ledger)
                .legalEntity(legalEntity)
                .combination(combination)
                .combinationCode(buildCombinationCode(combination))
                .description("Auto-registered during posting")
                .isDynamic(true)
                .firstUsedAt(Instant.now())
                .build();
        entity.setCreatedBy(performedBy);
        entity.setUpdatedBy(performedBy);

        AccountCombination saved = repository.save(entity);
        auditService.log(AuditAction.CREATE, "account_combination", saved.getId(), null,
                mapper.toResponse(saved), performedBy);
        return saved;
    }

    @Transactional
    public AccountCombinationResponse create(CreateAccountCombinationRequest request, String performedBy) {
        Ledger ledger = ledgerRepository.findById(request.ledgerId())
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", request.ledgerId()));
        LegalEntity legalEntity = legalEntityRepository.findById(request.legalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", request.legalEntityId()));

        if (repository.findByLedgerIdAndLegalEntityIdAndCombination(
                request.ledgerId(), request.legalEntityId(), toJson(request.combination())).isPresent()) {
            throw new DuplicateResourceException("DUPLICATE_COMBINATION",
                    "This account combination is already registered for this Ledger and Legal Entity.",
                    "combination");
        }

        AccountCombination entity = AccountCombination.builder()
                .ledger(ledger)
                .legalEntity(legalEntity)
                .combination(request.combination())
                .combinationCode(buildCombinationCode(request.combination()))
                .description(request.description())
                .isDynamic(false)
                .build();
        entity.setCreatedBy(performedBy);
        entity.setUpdatedBy(performedBy);

        AccountCombination saved = repository.save(entity);
        AccountCombinationResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.CREATE, "account_combination", saved.getId(), null, response, performedBy);

        return response;
    }

    @Transactional
    public AccountCombinationResponse update(UUID id, UpdateAccountCombinationRequest request, String performedBy) {
        AccountCombination entity = findOrThrow(id);
        AccountCombinationResponse before = mapper.toResponse(entity);

        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.isActive() != null) {
            entity.setActive(request.isActive());
        }
        entity.setUpdatedBy(performedBy);

        AccountCombination saved = repository.save(entity);
        AccountCombinationResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.UPDATE, "account_combination", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional
    public AccountCombinationResponse deactivate(UUID id, String performedBy) {
        AccountCombination entity = findOrThrow(id);
        AccountCombinationResponse before = mapper.toResponse(entity);

        entity.setActive(false);
        entity.setUpdatedBy(performedBy);

        AccountCombination saved = repository.save(entity);
        AccountCombinationResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.DELETE, "account_combination", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional(readOnly = true)
    public List<AccountCombinationResponse> list(UUID ledgerId, UUID legalEntityId, String costCentre,
                                                  String product, Boolean isActive) {
        Map<String, String> filter = new TreeMap<>();
        if (costCentre != null) {
            filter.put(DimensionType.COST_CENTRE.name(), costCentre);
        }
        if (product != null) {
            filter.put(DimensionType.PRODUCT.name(), product);
        }

        return repository.searchByFilter(ledgerId, legalEntityId, toJson(filter), isActive).stream()
                .map(mapper::toResponse)
                .toList();
    }

    private String buildCombinationCode(Map<String, String> combination) {
        StringBuilder code = new StringBuilder();
        code.append(combination.getOrDefault(DimensionType.NATURAL_ACCOUNT.name(), ""));
        if (combination.containsKey(DimensionType.COST_CENTRE.name())) {
            code.append('.').append(combination.get(DimensionType.COST_CENTRE.name()));
        }
        if (combination.containsKey(DimensionType.PRODUCT.name())) {
            code.append('.').append(combination.get(DimensionType.PRODUCT.name()));
        }
        return code.toString();
    }

    private AccountCombination findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AccountCombination", id));
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize account combination", e);
        }
    }
}
