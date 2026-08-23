package com.evyoog.gl.posting.service;

import com.evyoog.gl.aie.repository.SlaEventLogRepository;
import com.evyoog.gl.coa.domain.CoaStructure;
import com.evyoog.gl.combination.service.AccountCombinationService;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.periodstatus.service.PeriodStatusService;
import com.evyoog.gl.posting.domain.AccountBalance;
import com.evyoog.gl.posting.domain.JournalCategory;
import com.evyoog.gl.posting.domain.JournalHeader;
import com.evyoog.gl.posting.domain.JournalSource;
import com.evyoog.gl.posting.domain.JournalStatus;
import com.evyoog.gl.posting.dto.PostingLineRequest;
import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.dto.PostingResult;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.posting.repository.JournalCategoryRepository;
import com.evyoog.gl.posting.repository.JournalHeaderRepository;
import com.evyoog.gl.posting.repository.JournalSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostingEngineTest {

    @Mock private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock private LegalEntityRepository legalEntityRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private JournalSourceRepository journalSourceRepository;
    @Mock private JournalCategoryRepository journalCategoryRepository;
    @Mock private JournalHeaderRepository journalHeaderRepository;
    @Mock private AccountBalanceRepository accountBalanceRepository;
    @Mock private DimensionValueRepository dimensionValueRepository;
    @Mock private FinanceDimensionRepository financeDimensionRepository;
    @Mock private SlaEventLogRepository slaEventLogRepository;
    @Mock private PeriodStatusService periodStatusService;
    @Mock private AccountCombinationService accountCombinationService;
    @Mock private AuditService auditService;

    private PostingEngine postingEngine;

    private UUID legalEntityId;
    private UUID ledgerId;
    private UUID periodId;
    private UUID sourceId;
    private UUID categoryId;
    private UUID naturalAcctDimId;
    private UUID cashAccountId;
    private UUID revenueAccountId;

    private Ledger ledger;
    private DimensionValue cashAccount;
    private DimensionValue revenueAccount;

    @BeforeEach
    void setUp() {
        postingEngine = new PostingEngine(legalEntityLedgerRepository, legalEntityRepository,
                accountingPeriodRepository, journalSourceRepository, journalCategoryRepository,
                journalHeaderRepository, accountBalanceRepository, dimensionValueRepository,
                financeDimensionRepository, slaEventLogRepository, periodStatusService,
                accountCombinationService, auditService);

        legalEntityId = UUID.randomUUID();
        ledgerId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        sourceId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        naturalAcctDimId = UUID.randomUUID();
        cashAccountId = UUID.randomUUID();
        revenueAccountId = UUID.randomUUID();

        ledger = Ledger.builder().id(ledgerId).code("LDG").name("Primary Ledger")
                .financeMode(FinanceMode.THICK).functionalCurrency("INR").build();

        FinanceDimension naturalAcctDim = FinanceDimension.builder().id(naturalAcctDimId)
                .dimensionType(DimensionType.NATURAL_ACCOUNT).code("NA").name("Natural Account").build();

        cashAccount = DimensionValue.builder().id(cashAccountId).code("1000").name("Cash")
                .financeDimension(naturalAcctDim).isPostable(true).isSummary(false).build();
        revenueAccount = DimensionValue.builder().id(revenueAccountId).code("4000").name("Revenue")
                .financeDimension(naturalAcctDim).isPostable(true).isSummary(false).build();

        lenient().when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger));
        lenient().when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(naturalAcctDim));
        lenient().when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId))
                .thenReturn(List.of(naturalAcctDim));
        lenient().when(dimensionValueRepository
                .findByIdAndFinanceDimensionIdAndIsActiveTrue(cashAccountId, naturalAcctDimId))
                .thenReturn(Optional.of(cashAccount));
        lenient().when(dimensionValueRepository
                .findByIdAndFinanceDimensionIdAndIsActiveTrue(revenueAccountId, naturalAcctDimId))
                .thenReturn(Optional.of(revenueAccount));
        lenient().when(dimensionValueRepository.findById(cashAccountId)).thenReturn(Optional.of(cashAccount));
        lenient().when(dimensionValueRepository.findById(revenueAccountId)).thenReturn(Optional.of(revenueAccount));
        lenient().when(legalEntityLedgerRepository
                        .existsByLegalEntityIdAndLedgerIdAndIsActiveTrue(legalEntityId, ledgerId))
                .thenReturn(true);
        lenient().doNothing().when(periodStatusService).validatePeriodOpen(legalEntityId, periodId);

        JournalSource source = JournalSource.builder().id(sourceId).code("MANUAL").name("Manual Journal Entry")
                .requiresApproval(false).build();
        lenient().when(journalSourceRepository.findById(sourceId)).thenReturn(Optional.of(source));

        JournalCategory category = JournalCategory.builder().id(categoryId).code("ADJUSTMENT").name("Adjustment").build();
        lenient().when(journalCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        lenient().when(legalEntityRepository.findById(legalEntityId))
                .thenReturn(Optional.of(LegalEntity.builder().id(legalEntityId).build()));
        lenient().when(accountingPeriodRepository.findById(periodId))
                .thenReturn(Optional.of(AccountingPeriod.builder().id(periodId).build()));

        lenient().when(journalHeaderRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        lenient().when(journalHeaderRepository.save(any(JournalHeader.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accountBalanceRepository
                        .findByLedgerIdAndLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(accountBalanceRepository.save(any(AccountBalance.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private PostingRequest balancedThickRequest() {
        return PostingRequest.builder()
                .legalEntityId(legalEntityId)
                .accountingPeriodId(periodId)
                .journalSourceId(sourceId)
                .journalCategoryId(categoryId)
                .description("Test journal")
                .glDate(LocalDate.now())
                .accountingDate(LocalDate.now())
                .currencyCode("INR")
                .exchangeRate(BigDecimal.ONE)
                .performedBy("tester")
                .lines(List.of(
                        PostingLineRequest.builder().naturalAccountValueId(cashAccountId)
                                .debitAmount(new BigDecimal("100.00")).build(),
                        PostingLineRequest.builder().naturalAccountValueId(revenueAccountId)
                                .creditAmount(new BigDecimal("100.00")).build()))
                .build();
    }

    @Test
    void testPostThick_balanced_success() {
        PostingResult result = postingEngine.post(balancedThickRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getModeUsed()).isEqualTo(FinanceMode.THICK);
        assertThat(result.getJournalNumber()).matches("JE-\\d{4}-\\d{5}");
    }

    @Test
    void testPostThick_unbalanced_throwsJOURNAL_NOT_BALANCED() {
        PostingRequest request = balancedThickRequest();
        request.setLines(List.of(
                PostingLineRequest.builder().naturalAccountValueId(cashAccountId)
                        .debitAmount(new BigDecimal("100.00")).build(),
                PostingLineRequest.builder().naturalAccountValueId(revenueAccountId)
                        .creditAmount(new BigDecimal("50.00")).build()));

        assertThatThrownBy(() -> postingEngine.post(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "JOURNAL_NOT_BALANCED");
    }

    @Test
    void testPostThick_periodClosed_throwsPERIOD_NOT_OPEN() {
        doThrow(new EvyoogException("PERIOD_NOT_OPEN", "Period is CLOSED."))
                .when(periodStatusService).validatePeriodOpen(legalEntityId, periodId);

        assertThatThrownBy(() -> postingEngine.post(balancedThickRequest()))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "PERIOD_NOT_OPEN");
    }

    @Test
    void testPostThick_summaryAccount_throwsSUMMARY_ACCOUNT_NOT_POSTABLE() {
        cashAccount.setSummary(true);

        assertThatThrownBy(() -> postingEngine.post(balancedThickRequest()))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "SUMMARY_ACCOUNT_NOT_POSTABLE");
    }

    @Test
    void testPostThick_nonPostableAccount_throwsACCOUNT_NOT_POSTABLE() {
        cashAccount.setPostable(false);

        assertThatThrownBy(() -> postingEngine.post(balancedThickRequest()))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_NOT_POSTABLE");
    }

    @Test
    void testPostThick_unknownAccount_throwsACCOUNT_NOT_FOUND() {
        UUID unknownAccountId = UUID.randomUUID();
        when(dimensionValueRepository.findByIdAndFinanceDimensionIdAndIsActiveTrue(unknownAccountId, naturalAcctDimId))
                .thenReturn(Optional.empty());

        PostingRequest request = balancedThickRequest();
        request.setLines(List.of(
                PostingLineRequest.builder().naturalAccountValueId(unknownAccountId)
                        .debitAmount(new BigDecimal("100.00")).build(),
                PostingLineRequest.builder().naturalAccountValueId(revenueAccountId)
                        .creditAmount(new BigDecimal("100.00")).build()));

        assertThatThrownBy(() -> postingEngine.post(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_NOT_FOUND");
    }

    @Test
    void testPostThick_singleLine_throwsMINIMUM_TWO_LINES() {
        PostingRequest request = balancedThickRequest();
        request.setLines(List.of(
                PostingLineRequest.builder().naturalAccountValueId(cashAccountId)
                        .debitAmount(new BigDecimal("100.00")).build()));

        assertThatThrownBy(() -> postingEngine.post(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MINIMUM_TWO_LINES");
    }

    @Test
    void testPostThin_skipsApprovalRule_success() {
        ledger.setFinanceMode(FinanceMode.THIN);
        JournalSource approvalRequiredSource = JournalSource.builder().id(sourceId).code("MANUAL")
                .name("Manual Journal Entry").requiresApproval(true).build();
        when(journalSourceRepository.findById(sourceId)).thenReturn(Optional.of(approvalRequiredSource));

        PostingResult result = postingEngine.post(balancedThickRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getModeUsed()).isEqualTo(FinanceMode.THIN);
    }

    @Test
    void testPostThin_skipsCurrencyRule_success() {
        ledger.setFinanceMode(FinanceMode.THIN);
        PostingRequest request = balancedThickRequest();
        request.setCurrencyCode("USD");
        request.setExchangeRate(null);

        PostingResult result = postingEngine.post(request);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testPostThick_crossCurrencyWithoutExchangeRate_throwsCURRENCY_EXCHANGE_RATE_REQUIRED() {
        PostingRequest request = balancedThickRequest();
        request.setCurrencyCode("USD");
        request.setExchangeRate(null);

        assertThatThrownBy(() -> postingEngine.post(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "CURRENCY_EXCHANGE_RATE_REQUIRED");
    }

    @Test
    void testPostThick_approvalRequiredSource_throwsAPPROVAL_REQUIRED() {
        JournalSource approvalRequiredSource = JournalSource.builder().id(sourceId).code("MANUAL")
                .name("Manual Journal Entry").requiresApproval(true).build();
        when(journalSourceRepository.findById(sourceId)).thenReturn(Optional.of(approvalRequiredSource));

        assertThatThrownBy(() -> postingEngine.post(balancedThickRequest()))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "APPROVAL_REQUIRED");
    }

    @Test
    void testPostEventOnly_noJournalCreated() {
        ledger.setFinanceMode(FinanceMode.EVENT_ONLY);
        when(slaEventLogRepository.save(any())).thenAnswer(inv -> {
            var event = inv.getArgument(0, com.evyoog.gl.aie.domain.SlaEventLog.class);
            event.setId(UUID.randomUUID());
            return event;
        });
        PostingRequest request = balancedThickRequest();

        PostingResult result = postingEngine.post(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getJournalHeaderId()).isNull();
        assertThat(result.getSlaEventLogId()).isNotNull();
        verify(journalHeaderRepository, org.mockito.Mockito.never()).save(any());
        verify(accountBalanceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void testPostEventOnly_noPeriodIdProvided_throws() {
        ledger.setFinanceMode(FinanceMode.EVENT_ONLY);
        PostingRequest request = balancedThickRequest();
        request.setAccountingPeriodId(null);

        assertThatThrownBy(() -> postingEngine.post(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "PERIOD_REQUIRED_FOR_EVENT");
    }

    @Test
    void testFinanceModeSnapshot_thick_writtenCorrectly() {
        ArgumentCaptor<JournalHeader> captor = ArgumentCaptor.forClass(JournalHeader.class);
        postingEngine.post(balancedThickRequest());
        verify(journalHeaderRepository).save(captor.capture());
        assertThat(captor.getValue().getFinanceModeSnapshot()).isEqualTo(FinanceMode.THICK);
        assertThat(captor.getValue().getStatus()).isEqualTo(JournalStatus.POSTED);
    }

    @Test
    void testFinanceModeSnapshot_thin_writtenCorrectly() {
        ledger.setFinanceMode(FinanceMode.THIN);
        ArgumentCaptor<JournalHeader> captor = ArgumentCaptor.forClass(JournalHeader.class);
        postingEngine.post(balancedThickRequest());
        verify(journalHeaderRepository).save(captor.capture());
        assertThat(captor.getValue().getFinanceModeSnapshot()).isEqualTo(FinanceMode.THIN);
    }

    @Test
    void testAccountBalanceUpdate_debit_updatesCorrectly() {
        ArgumentCaptor<AccountBalance> captor = ArgumentCaptor.forClass(AccountBalance.class);
        postingEngine.post(balancedThickRequest());
        verify(accountBalanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        AccountBalance debitBalance = captor.getAllValues().stream()
                .filter(b -> b.getNaturalAccount().getId().equals(cashAccountId)).findFirst().orElseThrow();
        assertThat(debitBalance.getPeriodToDateDr()).isEqualByComparingTo("100.00");
        assertThat(debitBalance.getPeriodToDateCr()).isEqualByComparingTo("0.00");
    }

    @Test
    void testAccountBalanceUpdate_credit_updatesCorrectly() {
        ArgumentCaptor<AccountBalance> captor = ArgumentCaptor.forClass(AccountBalance.class);
        postingEngine.post(balancedThickRequest());
        verify(accountBalanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        AccountBalance creditBalance = captor.getAllValues().stream()
                .filter(b -> b.getNaturalAccount().getId().equals(revenueAccountId)).findFirst().orElseThrow();
        assertThat(creditBalance.getPeriodToDateCr()).isEqualByComparingTo("100.00");
        assertThat(creditBalance.getPeriodToDateDr()).isEqualByComparingTo("0.00");
    }

    @Test
    void testAccountBalanceUpdate_newBalance_createdWhenNotExists() {
        when(accountBalanceRepository
                .findByLedgerIdAndLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(any(), any(), any(), any()))
                .thenReturn(List.of());

        postingEngine.post(balancedThickRequest());

        verify(accountBalanceRepository, org.mockito.Mockito.times(2)).save(any(AccountBalance.class));
    }

    @Test
    void testJournalNumber_formatCorrect() {
        when(journalHeaderRepository.countByCreatedAtAfter(any())).thenReturn(4L);
        PostingResult result = postingEngine.post(balancedThickRequest());
        assertThat(result.getJournalNumber()).matches("JE-\\d{4}-00005");
    }

    // ── Rule 9 — account combination / required dimension validation ───────

    private static final UUID COST_CTR_DIM_ID = UUID.randomUUID();
    private static final UUID CC_MFG_ID = UUID.randomUUID();

    private FinanceDimension requiredCostCentreDimension() {
        return FinanceDimension.builder().id(COST_CTR_DIM_ID)
                .dimensionType(DimensionType.COST_CENTRE).code("COST-CTR").name("Cost Centre")
                .isRequired(true).build();
    }

    private PostingRequest requestWithCombination(Map<String, String> combination) {
        PostingRequest request = balancedThickRequest();
        request.setLines(List.of(
                PostingLineRequest.builder().naturalAccountValueId(cashAccountId)
                        .accountCombination(combination)
                        .debitAmount(new BigDecimal("100.00")).build(),
                PostingLineRequest.builder().naturalAccountValueId(revenueAccountId)
                        .accountCombination(combination)
                        .creditAmount(new BigDecimal("100.00")).build()));
        return request;
    }

    @Test
    void testValidAccountCombination_allDimensionsValid_passes() {
        FinanceDimension costCentreDim = requiredCostCentreDimension();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository
                .findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(CC_MFG_ID).code("CC-MFG").build()));

        PostingResult result = postingEngine.post(requestWithCombination(Map.of("COST_CENTRE", "CC-MFG")));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testInvalidDimensionCode_throwsINVALID_DIMENSION_TYPE() {
        assertThatThrownBy(() -> postingEngine.post(requestWithCombination(Map.of("NOT-A-TYPE", "CC-MFG"))))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DIMENSION_TYPE");
    }

    @Test
    void testInvalidDimensionValue_throwsDIMENSION_VALUE_NOT_FOUND() {
        FinanceDimension costCentreDim = requiredCostCentreDimension();
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository
                .findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-UNKNOWN"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> postingEngine.post(requestWithCombination(Map.of("COST_CENTRE", "CC-UNKNOWN"))))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "DIMENSION_VALUE_NOT_FOUND");
    }

    @Test
    void testMissingRequiredDimension_throwsMISSING_REQUIRED_DIMENSION() {
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId))
                .thenReturn(List.of(requiredCostCentreDimension()));

        assertThatThrownBy(() -> postingEngine.post(requestWithCombination(Map.of())))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MISSING_REQUIRED_DIMENSION");
    }

    @Test
    void testOptionalDimensionAbsent_passes() {
        FinanceDimension optionalProductDim = FinanceDimension.builder().id(UUID.randomUUID())
                .dimensionType(DimensionType.PRODUCT).code("PRODUCT").name("Product").isRequired(false).build();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId))
                .thenReturn(List.of(optionalProductDim));

        PostingResult result = postingEngine.post(requestWithCombination(Map.of()));

        assertThat(result.isSuccess()).isTrue();
    }

    // ── Rule 10 — account combination registry validation ──────────────────

    @Test
    void testAccountCombination_emptyCombination_skipsRegistryCheck() {
        PostingRequest request = balancedThickRequest();

        PostingResult result = postingEngine.post(request);

        assertThat(result.isSuccess()).isTrue();
        verifyNoInteractions(accountCombinationService);
    }

    @Test
    void testAccountCombination_registryRejectsCombination_propagatesException() {
        ledger.setAllowDynamicInsert(false);
        FinanceDimension costCentreDim = requiredCostCentreDimension();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository
                .findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(CC_MFG_ID).code("CC-MFG").build()));
        when(accountCombinationService.validate(eq(ledgerId), eq(legalEntityId), any(), eq(false), anyString()))
                .thenThrow(new EvyoogException("INVALID_ACCOUNT_COMBINATION", "not approved"));

        assertThatThrownBy(() -> postingEngine.post(requestWithCombination(Map.of("COST_CENTRE", "CC-MFG"))))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ACCOUNT_COMBINATION");
    }

    @Test
    void testAccountCombination_dynamicInsertFlagReadFromLedger_passedToService() {
        ledger.setAllowDynamicInsert(false);
        FinanceDimension costCentreDim = requiredCostCentreDimension();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository
                .findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(CC_MFG_ID).code("CC-MFG").build()));

        postingEngine.post(requestWithCombination(Map.of("COST_CENTRE", "CC-MFG")));

        verify(accountCombinationService, times(2))
                .validate(eq(ledgerId), eq(legalEntityId), any(), eq(false), anyString());
    }

    // ── Rule 4b — auto-insert default values for optional dimensions ───────

    private FinanceDimension optionalProductDimension() {
        return FinanceDimension.builder().id(UUID.randomUUID())
                .dimensionType(DimensionType.PRODUCT).code("PRODUCT").name("Product").isRequired(false).build();
    }

    @Test
    void testEnrichWithDefaults_addsDefaultForOptionalDimension() {
        FinanceDimension productDim = optionalProductDimension();
        DimensionValue defaultProduct = DimensionValue.builder().id(UUID.randomUUID()).code("SPARES").build();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId)).thenReturn(List.of(productDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsDefaultTrue(productDim.getId()))
                .thenReturn(Optional.of(defaultProduct));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.PRODUCT))
                .thenReturn(Optional.of(productDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(productDim.getId(), "SPARES"))
                .thenReturn(Optional.of(defaultProduct));

        ArgumentCaptor<JournalHeader> captor = ArgumentCaptor.forClass(JournalHeader.class);
        postingEngine.post(requestWithCombination(Map.of()));

        verify(journalHeaderRepository).save(captor.capture());
        assertThat(captor.getValue().getLines().get(0).getAccountCombination())
                .containsEntry("PRODUCT", "SPARES");
    }

    @Test
    void testEnrichWithDefaults_doesNotOverrideExplicitValue() {
        FinanceDimension productDim = optionalProductDimension();
        DimensionValue defaultProduct = DimensionValue.builder().id(UUID.randomUUID()).code("SPARES").build();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId)).thenReturn(List.of(productDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.PRODUCT))
                .thenReturn(Optional.of(productDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(productDim.getId(), "GATE-VLV"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("GATE-VLV").build()));

        ArgumentCaptor<JournalHeader> captor = ArgumentCaptor.forClass(JournalHeader.class);
        postingEngine.post(requestWithCombination(Map.of("PRODUCT", "GATE-VLV")));

        verify(journalHeaderRepository).save(captor.capture());
        assertThat(captor.getValue().getLines().get(0).getAccountCombination())
                .containsEntry("PRODUCT", "GATE-VLV");
        verify(dimensionValueRepository, org.mockito.Mockito.never())
                .findByFinanceDimensionIdAndIsDefaultTrue(productDim.getId());
    }

    @Test
    void testEnrichWithDefaults_skipsRequiredDimensions() {
        FinanceDimension costCentreDim = requiredCostCentreDimension();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId)).thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(CC_MFG_ID).code("CC-MFG").build()));

        postingEngine.post(requestWithCombination(Map.of("COST_CENTRE", "CC-MFG")));

        verify(dimensionValueRepository, org.mockito.Mockito.never())
                .findByFinanceDimensionIdAndIsDefaultTrue(COST_CTR_DIM_ID);
    }

    @Test
    void testEnrichWithDefaults_skipsNaturalAccount() {
        postingEngine.post(balancedThickRequest());

        verify(dimensionValueRepository, org.mockito.Mockito.never())
                .findByFinanceDimensionIdAndIsDefaultTrue(naturalAcctDimId);
    }

    @Test
    void testEnrichWithDefaults_noDefaultDefined_omitsFromCombination() {
        FinanceDimension productDim = optionalProductDimension();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId)).thenReturn(List.of(productDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsDefaultTrue(productDim.getId()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<JournalHeader> captor = ArgumentCaptor.forClass(JournalHeader.class);
        PostingResult result = postingEngine.post(requestWithCombination(Map.of()));

        assertThat(result.isSuccess()).isTrue();
        verify(journalHeaderRepository).save(captor.capture());
        assertThat(captor.getValue().getLines().get(0).getAccountCombination()).doesNotContainKey("PRODUCT");
    }

    @Test
    void testPostingEngine_enrichesOptionalDimensionWithDefault() {
        FinanceDimension productDim = optionalProductDimension();
        DimensionValue defaultProduct = DimensionValue.builder().id(UUID.randomUUID()).code("SPARES").build();
        when(financeDimensionRepository.findByLedgerIdAndIsActiveTrue(ledgerId)).thenReturn(List.of(productDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsDefaultTrue(productDim.getId()))
                .thenReturn(Optional.of(defaultProduct));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.PRODUCT))
                .thenReturn(Optional.of(productDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(productDim.getId(), "SPARES"))
                .thenReturn(Optional.of(defaultProduct));

        postingEngine.post(requestWithCombination(Map.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> combinationCaptor = ArgumentCaptor.forClass(Map.class);
        verify(accountCombinationService, times(2))
                .validate(eq(ledgerId), eq(legalEntityId), combinationCaptor.capture(), eq(true), anyString());
        assertThat(combinationCaptor.getValue()).containsEntry("PRODUCT", "SPARES");
    }

    // ── Rule 11 — balancing segment crossing check ──────────────────────────

    private static final UUID COA_STRUCTURE_ID = UUID.randomUUID();
    private static final UUID BALANCING_PRODUCT_DIM_ID = UUID.randomUUID();

    private FinanceDimension balancingCostCentreDimension() {
        return FinanceDimension.builder().id(COST_CTR_DIM_ID)
                .dimensionType(DimensionType.COST_CENTRE).code("COST-CTR").name("Cost Centre")
                .isBalancing(true).balancingSequence(2).build();
    }

    private FinanceDimension balancingProductDimension() {
        return FinanceDimension.builder().id(BALANCING_PRODUCT_DIM_ID)
                .dimensionType(DimensionType.PRODUCT).code("PRODUCT").name("Product")
                .isBalancing(true).balancingSequence(3).build();
    }

    private PostingRequest requestWithLineCombinations(Map<String, String> line1Combo, Map<String, String> line2Combo) {
        PostingRequest request = balancedThickRequest();
        request.setLines(List.of(
                PostingLineRequest.builder().naturalAccountValueId(cashAccountId)
                        .accountCombination(line1Combo)
                        .debitAmount(new BigDecimal("100.00")).build(),
                PostingLineRequest.builder().naturalAccountValueId(revenueAccountId)
                        .accountCombination(line2Combo)
                        .creditAmount(new BigDecimal("100.00")).build()));
        return request;
    }

    @Test
    void testBalancingSegment_allLinesSameCostCentre_passes() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        FinanceDimension costCentreDim = balancingCostCentreDimension();
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-MFG").build()));

        PostingResult result = postingEngine.post(
                requestWithLineCombinations(Map.of("COST_CENTRE", "CC-MFG"), Map.of("COST_CENTRE", "CC-MFG")));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testBalancingSegment_differentCostCentres_throws() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        FinanceDimension costCentreDim = balancingCostCentreDimension();
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-MFG").build()));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-SAL"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-SAL").build()));

        assertThatThrownBy(() -> postingEngine.post(
                requestWithLineCombinations(Map.of("COST_CENTRE", "CC-MFG"), Map.of("COST_CENTRE", "CC-SAL"))))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "BALANCING_SEGMENT_CROSSED");
    }

    @Test
    void testBalancingSegment_linesMissingCostCentre_throws() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        FinanceDimension costCentreDim = balancingCostCentreDimension();
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-MFG").build()));

        assertThatThrownBy(() -> postingEngine.post(
                requestWithLineCombinations(Map.of("COST_CENTRE", "CC-MFG"), Map.of())))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "BALANCING_SEGMENT_CROSSED");
    }

    @Test
    void testBalancingSegment_noBalancingDimensions_passes() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of());

        PostingResult result = postingEngine.post(balancedThickRequest());

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testBalancingSegment_coaStructureNull_passes() {
        // ledger.coaStructure is null by default (see setUp) — Rule 11 must
        // short-circuit without ever querying the balancing-dimension repo.
        PostingResult result = postingEngine.post(balancedThickRequest());

        assertThat(result.isSuccess()).isTrue();
        verify(financeDimensionRepository, org.mockito.Mockito.never())
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(any());
    }

    @Test
    void testBalancingSegment_twoBalancingDims_bothMustMatch() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        FinanceDimension costCentreDim = balancingCostCentreDimension();
        FinanceDimension productDim = balancingProductDimension();
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of(costCentreDim, productDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-MFG").build()));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.PRODUCT))
                .thenReturn(Optional.of(productDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(BALANCING_PRODUCT_DIM_ID, "GATE-VLV"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("GATE-VLV").build()));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(BALANCING_PRODUCT_DIM_ID, "BALL-VLV"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("BALL-VLV").build()));

        // COST_CENTRE matches on both lines, but PRODUCT differs — one
        // matching balancing dimension does not excuse a mismatch on another.
        assertThatThrownBy(() -> postingEngine.post(requestWithLineCombinations(
                Map.of("COST_CENTRE", "CC-MFG", "PRODUCT", "GATE-VLV"),
                Map.of("COST_CENTRE", "CC-MFG", "PRODUCT", "BALL-VLV"))))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "BALANCING_SEGMENT_CROSSED");
    }

    @Test
    void testBalancingSegment_errorMessage_containsDimensionName() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        FinanceDimension costCentreDim = balancingCostCentreDimension();
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-MFG").build()));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-SAL"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-SAL").build()));

        assertThatThrownBy(() -> postingEngine.post(
                requestWithLineCombinations(Map.of("COST_CENTRE", "CC-MFG"), Map.of("COST_CENTRE", "CC-SAL"))))
                .hasMessageContaining("Cost Centre");
    }

    @Test
    void testBalancingSegment_errorMessage_containsValuesFound() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        FinanceDimension costCentreDim = balancingCostCentreDimension();
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-MFG").build()));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-SAL"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-SAL").build()));

        assertThatThrownBy(() -> postingEngine.post(
                requestWithLineCombinations(Map.of("COST_CENTRE", "CC-MFG"), Map.of("COST_CENTRE", "CC-SAL"))))
                .hasMessageContaining("CC-MFG")
                .hasMessageContaining("CC-SAL");
    }

    @Test
    void testBalancingSegment_onlySecondaryEnforced_notPrimary() {
        ledger.setCoaStructure(CoaStructure.builder().id(COA_STRUCTURE_ID).build());
        FinanceDimension costCentreDim = balancingCostCentreDimension();
        when(financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(COA_STRUCTURE_ID))
                .thenReturn(List.of(costCentreDim));
        when(financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.COST_CENTRE))
                .thenReturn(Optional.of(costCentreDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(COST_CTR_DIM_ID, "CC-MFG"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-MFG").build()));

        // Neither line carries a LEGAL_ENTITY key in its account_combination —
        // the implicit primary balancing segment is never a JSONB key, and
        // Rule 11 only enforces whatever findByCoaStructureIdAndIsBalancingTrue
        // returns (secondary/tertiary only).
        PostingResult result = postingEngine.post(
                requestWithLineCombinations(Map.of("COST_CENTRE", "CC-MFG"), Map.of("COST_CENTRE", "CC-MFG")));

        assertThat(result.isSuccess()).isTrue();
        verify(financeDimensionRepository, org.mockito.Mockito.never())
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.LEGAL_ENTITY);
    }
}
