package com.evyoog.gl.coa.service;

import com.evyoog.gl.coa.domain.ProvisioningTemplate;
import com.evyoog.gl.coa.dto.AccountResponse;
import com.evyoog.gl.coa.dto.AccountTreeResponse;
import com.evyoog.gl.coa.dto.CreateAccountRequest;
import com.evyoog.gl.coa.dto.UpdateAccountRequest;
import com.evyoog.gl.coa.mapper.AccountMapper;
import com.evyoog.gl.coa.repository.ProvisioningTemplateRepository;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.dto.CreateDimensionValueRequest;
import com.evyoog.gl.dimension.dto.DimensionValueResponse;
import com.evyoog.gl.dimension.dto.UpdateDimensionValueRequest;
import com.evyoog.gl.dimension.mapper.DimensionValueMapper;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.dimension.service.DimensionValueService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChartOfAccountsService {

    private final FinanceDimensionRepository financeDimensionRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final ProvisioningTemplateRepository provisioningTemplateRepository;
    private final DimensionValueService dimensionValueService;
    private final DimensionValueMapper dimensionValueMapper;
    private final AccountMapper accountMapper;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AccountTreeResponse getAccountTree(UUID ledgerId, AccountQualifier qualifier) {
        FinanceDimension dimension = naturalAccountDimension(ledgerId);

        List<DimensionValue> values = dimensionValueRepository.findByFinanceDimensionIdAndIsActiveTrue(dimension.getId());
        if (qualifier != null) {
            values = values.stream().filter(v -> v.getAccountQualifier() == qualifier).toList();
        }

        Map<UUID, List<DimensionValue>> childrenByParent = values.stream()
                .filter(v -> v.getParentValue() != null)
                .collect(Collectors.groupingBy(v -> v.getParentValue().getId()));

        List<AccountResponse> roots = values.stream()
                .filter(v -> v.getParentValue() == null)
                .sorted(Comparator.comparingInt(DimensionValue::getDisplayOrder))
                .map(v -> buildNode(v, childrenByParent))
                .toList();

        long postableCount = values.stream().filter(DimensionValue::isPostable).count();
        long summaryCount = values.stream().filter(DimensionValue::isSummary).count();

        return new AccountTreeResponse(ledgerId, dimension.getId(), values.size(), postableCount, summaryCount, roots);
    }

    @Transactional(readOnly = true)
    public AccountResponse getById(UUID accountId) {
        DimensionValue entity = dimensionValueRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        return toAccountResponse(entity).withChildren(List.of());
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getPostableAccounts(UUID ledgerId) {
        FinanceDimension dimension = naturalAccountDimension(ledgerId);
        LocalDate today = LocalDate.now();

        return dimensionValueRepository.findByFinanceDimensionIdAndIsPostableTrueAndIsActiveTrue(dimension.getId()).stream()
                .filter(v -> v.getValidFrom() == null || !v.getValidFrom().isAfter(today))
                .filter(v -> v.getValidTo() == null || !v.getValidTo().isBefore(today))
                .sorted(Comparator.comparingInt(DimensionValue::getDisplayOrder))
                .map(v -> toAccountResponse(v).withChildren(List.of()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> search(UUID ledgerId, String query) {
        FinanceDimension dimension = naturalAccountDimension(ledgerId);
        return dimensionValueRepository.search(dimension.getId(), query).stream()
                .map(v -> toAccountResponse(v).withChildren(List.of()))
                .toList();
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, String performedBy) {
        FinanceDimension dimension = naturalAccountDimension(request.ledgerId());

        boolean isSummary = request.isSummary() != null && request.isSummary();
        boolean isPostable = !isSummary && (request.isPostable() == null || request.isPostable());

        CreateDimensionValueRequest dvRequest = new CreateDimensionValueRequest(
                dimension.getId(), request.code(), request.name(), request.description(),
                request.parentAccountId(), request.qualifier(), isSummary, isPostable,
                request.normalBalance(), request.gstApplicable(), request.tdsApplicable(),
                request.tdsSection(), request.displayOrder(), null,
                request.counterpartyLegalEntityId(), request.ccManagerName(), request.ccManagerEmail(),
                request.ccDepartment(), request.validFrom(), request.validTo(), request.budgetControlled(),
                request.extendedAttributes());

        DimensionValueResponse response = dimensionValueService.create(dvRequest, performedBy);
        return accountMapper.toResponse(response).withChildren(List.of());
    }

    @Transactional
    public AccountResponse updateAccount(UUID accountId, UpdateAccountRequest request, String performedBy) {
        Boolean isPostable = request.isPostable();
        if (Boolean.TRUE.equals(request.isSummary())) {
            isPostable = false;
        }

        UpdateDimensionValueRequest dvRequest = new UpdateDimensionValueRequest(
                request.name(), request.description(), request.isSummary(), isPostable,
                request.gstApplicable(), request.tdsApplicable(), request.tdsSection(), request.displayOrder(), null,
                request.counterpartyLegalEntityId(), request.ccManagerName(), request.ccManagerEmail(),
                request.ccDepartment(), request.validFrom(), request.validTo(), request.budgetControlled());

        DimensionValueResponse response = dimensionValueService.update(accountId, dvRequest, performedBy);
        return accountMapper.toResponse(response).withChildren(List.of());
    }

    @Transactional
    public void deactivateAccount(UUID accountId, String performedBy) {
        long activeChildren = dimensionValueRepository.countByParentValueIdAndIsActiveTrue(accountId);
        if (activeChildren > 0) {
            throw new EvyoogException("ACCOUNT_HAS_ACTIVE_CHILDREN",
                    "Cannot deactivate an account that has active child accounts.", HttpStatus.CONFLICT);
        }
        dimensionValueService.deactivate(accountId, performedBy);
    }

    @Transactional
    public int applyTemplate(UUID templateId, UUID ledgerId, String performedBy) {
        ProvisioningTemplate template = provisioningTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningTemplate", templateId));

        FinanceDimension dimension = naturalAccountDimension(ledgerId);

        JsonNode accounts = objectMapper.valueToTree(template.getTemplateData()).path("accounts");
        Map<String, UUID> codeToId = new LinkedHashMap<>();
        int order = 0;
        int created = 0;

        for (JsonNode account : accounts) {
            String code = account.path("code").asText();
            String name = account.path("name").asText();
            AccountQualifier qualifier = AccountQualifier.valueOf(account.path("qualifier").asText());
            boolean isSummary = account.path("isSummary").asBoolean(false);
            boolean isPostable = !isSummary && account.path("isPostable").asBoolean(true);
            boolean gstApplicable = account.path("gstApplicable").asBoolean(false);
            boolean tdsApplicable = account.path("tdsApplicable").asBoolean(false);
            String parentCode = account.hasNonNull("parentCode") ? account.path("parentCode").asText() : null;
            UUID parentId = parentCode != null ? codeToId.get(parentCode) : null;

            CreateDimensionValueRequest dvRequest = new CreateDimensionValueRequest(
                    dimension.getId(), code, name, null, parentId, qualifier, isSummary, isPostable,
                    null, gstApplicable, tdsApplicable, null, order, null,
                    null, null, null, null, null, null, null, null);

            DimensionValueResponse response = dimensionValueService.create(dvRequest, performedBy);
            codeToId.put(code, response.id());
            order++;
            created++;
        }

        return created;
    }

    private AccountResponse buildNode(DimensionValue value, Map<UUID, List<DimensionValue>> childrenByParent) {
        List<AccountResponse> children = childrenByParent.getOrDefault(value.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(DimensionValue::getDisplayOrder))
                .map(child -> buildNode(child, childrenByParent))
                .toList();
        return toAccountResponse(value).withChildren(children);
    }

    private AccountResponse toAccountResponse(DimensionValue entity) {
        return accountMapper.toResponse(dimensionValueMapper.toResponse(entity));
    }

    private FinanceDimension naturalAccountDimension(UUID ledgerId) {
        return financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT)
                .orElseThrow(() -> new EvyoogException("NO_NATURAL_ACCOUNT_DIM",
                        "No active Natural Account dimension found for this Ledger.", HttpStatus.NOT_FOUND));
    }
}
