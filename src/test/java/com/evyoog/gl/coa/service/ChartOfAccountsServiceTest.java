package com.evyoog.gl.coa.service;

import com.evyoog.gl.coa.domain.IndustryType;
import com.evyoog.gl.coa.domain.ProvisioningTemplate;
import com.evyoog.gl.coa.dto.AccountResponse;
import com.evyoog.gl.coa.dto.AccountTreeResponse;
import com.evyoog.gl.coa.dto.CreateAccountRequest;
import com.evyoog.gl.coa.mapper.AccountMapperImpl;
import com.evyoog.gl.coa.repository.ProvisioningTemplateRepository;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.dto.CreateDimensionValueRequest;
import com.evyoog.gl.dimension.dto.DimensionValueResponse;
import com.evyoog.gl.dimension.mapper.DimensionValueMapperImpl;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.dimension.service.DimensionValueService;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChartOfAccountsServiceTest {

    @Mock
    private FinanceDimensionRepository financeDimensionRepository;
    @Mock
    private DimensionValueRepository dimensionValueRepository;
    @Mock
    private ProvisioningTemplateRepository provisioningTemplateRepository;
    @Mock
    private DimensionValueService dimensionValueService;

    private ChartOfAccountsService service;

    @BeforeEach
    void setUp() {
        service = new ChartOfAccountsService(financeDimensionRepository, dimensionValueRepository,
                provisioningTemplateRepository, dimensionValueService, new DimensionValueMapperImpl(),
                new AccountMapperImpl(), new ObjectMapper());
    }

    private FinanceDimension naturalAccountDimension(UUID ledgerId) {
        Ledger ledger = Ledger.builder().code("LDG").name("Ledger").build();
        ledger.setId(ledgerId);
        FinanceDimension fd = FinanceDimension.builder().code("NA").name("Natural Account")
                .dimensionType(DimensionType.NATURAL_ACCOUNT).ledger(ledger).build();
        fd.setId(UUID.randomUUID());
        return fd;
    }

    private DimensionValue value(FinanceDimension fd, String code, String name, DimensionValue parent,
                                  AccountQualifier qualifier, boolean summary, boolean postable, int order) {
        DimensionValue v = DimensionValue.builder()
                .code(code).name(name).financeDimension(fd).parentValue(parent).accountQualifier(qualifier)
                .isSummary(summary).isPostable(postable).displayOrder(order).build();
        v.setId(UUID.randomUUID());
        v.setActive(true);
        return v;
    }

    @Test
    void getAccountTree_returnsNestedStructure() {
        UUID ledgerId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);
        DimensionValue root = value(fd, "1000", "Assets", null, AccountQualifier.ASSET, true, false, 0);
        DimensionValue child = value(fd, "1100", "Cash", root, AccountQualifier.ASSET, false, true, 0);

        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsActiveTrue(fd.getId()))
                .thenReturn(List.of(root, child));

        AccountTreeResponse tree = service.getAccountTree(ledgerId, null);

        assertThat(tree.totalCount()).isEqualTo(2);
        assertThat(tree.accounts()).hasSize(1);
        assertThat(tree.accounts().get(0).code()).isEqualTo("1000");
        assertThat(tree.accounts().get(0).children()).hasSize(1);
        assertThat(tree.accounts().get(0).children().get(0).code()).isEqualTo("1100");
    }

    @Test
    void getAccountTree_filterByQualifier() {
        UUID ledgerId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);
        DimensionValue asset = value(fd, "1000", "Assets", null, AccountQualifier.ASSET, true, false, 0);
        DimensionValue liability = value(fd, "2000", "Liabilities", null, AccountQualifier.LIABILITY, true, false, 1);

        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsActiveTrue(fd.getId()))
                .thenReturn(List.of(asset, liability));

        AccountTreeResponse tree = service.getAccountTree(ledgerId, AccountQualifier.ASSET);

        assertThat(tree.accounts()).hasSize(1);
        assertThat(tree.accounts().get(0).code()).isEqualTo("1000");
    }

    @Test
    void getPostableAccounts_excludesSummaryAccounts() {
        UUID ledgerId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);
        DimensionValue postable = value(fd, "1100", "Cash", null, AccountQualifier.ASSET, false, true, 0);

        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsPostableTrueAndIsActiveTrue(fd.getId()))
                .thenReturn(List.of(postable));

        List<AccountResponse> result = service.getPostableAccounts(ledgerId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).code()).isEqualTo("1100");
    }

    @Test
    void getPostableAccounts_excludesExpiredAccounts() {
        UUID ledgerId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);
        DimensionValue expired = value(fd, "1100", "Old Cash", null, AccountQualifier.ASSET, false, true, 0);
        expired.setValidTo(LocalDate.now().minusDays(1));
        DimensionValue current = value(fd, "1200", "Current Cash", null, AccountQualifier.ASSET, false, true, 1);

        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsPostableTrueAndIsActiveTrue(fd.getId()))
                .thenReturn(List.of(expired, current));

        List<AccountResponse> result = service.getPostableAccounts(ledgerId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).code()).isEqualTo("1200");
    }

    @Test
    void searchAccounts_byCode() {
        UUID ledgerId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);
        DimensionValue match = value(fd, "1110", "Cash", null, AccountQualifier.ASSET, false, true, 0);

        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueRepository.search(fd.getId(), "1110")).thenReturn(List.of(match));

        List<AccountResponse> result = service.search(ledgerId, "1110");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).code()).isEqualTo("1110");
    }

    @Test
    void searchAccounts_byName_caseInsensitive() {
        UUID ledgerId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);
        DimensionValue match = value(fd, "1110", "Cash and Bank", null, AccountQualifier.ASSET, false, true, 0);

        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueRepository.search(fd.getId(), "cash")).thenReturn(List.of(match));

        List<AccountResponse> result = service.search(ledgerId, "cash");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Cash and Bank");
    }

    @Test
    void createAccount_summaryForcesNotPostable() {
        UUID ledgerId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);
        CreateAccountRequest request = new CreateAccountRequest(ledgerId, "1000", "Assets", null, null,
                AccountQualifier.ASSET, true, true, null, null, null, null, null, null, null, null, null,
                null, null, null, null);

        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueService.create(any(CreateDimensionValueRequest.class), any()))
                .thenReturn(sampleResponse(fd, "1000", "Assets", true, false));

        service.createAccount(request, "prashanth");

        ArgumentCaptor<CreateDimensionValueRequest> captor = ArgumentCaptor.forClass(CreateDimensionValueRequest.class);
        verify(dimensionValueService).create(captor.capture(), any());
        assertThat(captor.getValue().isSummary()).isTrue();
        assertThat(captor.getValue().isPostable()).isFalse();
    }

    @Test
    void deactivateAccount_activeChildrenPreventsDeactivation_throws409() {
        UUID accountId = UUID.randomUUID();
        when(dimensionValueRepository.countByParentValueIdAndIsActiveTrue(accountId)).thenReturn(1L);

        assertThatThrownBy(() -> service.deactivateAccount(accountId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_HAS_ACTIVE_CHILDREN");
    }

    @Test
    void applyTemplate_createsAllAccounts_inCorrectOrder() {
        UUID ledgerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        FinanceDimension fd = naturalAccountDimension(ledgerId);

        Map<String, Object> templateData = Map.of("accounts", List.of(
                Map.of("code", "1000", "name", "Assets", "qualifier", "ASSET", "isSummary", true),
                Map.of("code", "1100", "name", "Cash", "qualifier", "ASSET", "isPostable", true, "parentCode", "1000")
        ));
        ProvisioningTemplate template = ProvisioningTemplate.builder()
                .code("TEST").name("Test Template").industryType(IndustryType.SERVICES)
                .financeMode(FinanceMode.THIN).templateData(templateData).build();
        template.setId(templateId);

        when(provisioningTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));
        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(fd));
        when(dimensionValueService.create(any(CreateDimensionValueRequest.class), any()))
                .thenAnswer(inv -> {
                    CreateDimensionValueRequest req = inv.getArgument(0);
                    return sampleResponse(fd, req.code(), req.name(), req.isSummary(), req.isPostable());
                });

        int created = service.applyTemplate(templateId, ledgerId, "prashanth");

        assertThat(created).isEqualTo(2);

        ArgumentCaptor<CreateDimensionValueRequest> captor = ArgumentCaptor.forClass(CreateDimensionValueRequest.class);
        verify(dimensionValueService, org.mockito.Mockito.times(2)).create(captor.capture(), any());
        List<CreateDimensionValueRequest> requests = captor.getAllValues();
        assertThat(requests.get(0).code()).isEqualTo("1000");
        assertThat(requests.get(1).code()).isEqualTo("1100");
        assertThat(requests.get(1).isPostable()).isTrue();
    }

    private DimensionValueResponse sampleResponse(FinanceDimension fd, String code, String name,
                                                   Boolean isSummary, Boolean isPostable) {
        return new DimensionValueResponse(UUID.randomUUID(), fd.getId(), fd.getCode(), fd.getName(),
                fd.getDimensionType(), code, name, null, null, null, null, AccountQualifier.ASSET,
                isSummary != null && isSummary, isPostable != null && isPostable, null, false, false, null, 0,
                false, true, null, null, null, null, null, null, null, false, null, null, null);
    }
}
