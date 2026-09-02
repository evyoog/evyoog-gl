package com.evyoog.gl.reporting.trialbalance.service;

import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.domain.NormalBalance;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.posting.domain.AccountBalance;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.reporting.trialbalance.dto.HierarchicalTrialBalanceLine;
import com.evyoog.gl.reporting.trialbalance.dto.HierarchicalTrialBalanceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HierarchicalTrialBalanceServiceTest {

    @Mock private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock private FinanceDimensionRepository financeDimensionRepository;
    @Mock private DimensionValueRepository dimensionValueRepository;
    @Mock private AccountBalanceRepository accountBalanceRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private LegalEntityRepository legalEntityRepository;

    private HierarchicalTrialBalanceService service;

    private UUID legalEntityId;
    private UUID periodId;
    private Ledger ledger;
    private FinanceDimension naturalAcctDim;
    private LegalEntity legalEntity;
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        service = new HierarchicalTrialBalanceService(legalEntityLedgerRepository, financeDimensionRepository,
                dimensionValueRepository, accountBalanceRepository, accountingPeriodRepository,
                legalEntityRepository, new ObjectMapper());

        legalEntityId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        ledger = Ledger.builder().id(UUID.randomUUID()).code("LDG").name("Primary Ledger")
                .financeMode(FinanceMode.THICK).build();
        naturalAcctDim = FinanceDimension.builder().code("NAT-ACCT").name("Natural Account")
                .dimensionType(DimensionType.NATURAL_ACCOUNT).ledger(ledger).build();
        naturalAcctDim.setId(UUID.randomUUID());
        legalEntity = LegalEntity.builder().id(legalEntityId).code("LE1").name("Legal Entity 1").build();
        period = AccountingPeriod.builder().id(periodId).name("APR-2025").fiscalYear("2025-26").build();
    }

    private DimensionValue account(String code, AccountQualifier qualifier, NormalBalance normalBalance,
                                    boolean isSummary, DimensionValue parent, int displayOrder) {
        DimensionValue dv = DimensionValue.builder().id(UUID.randomUUID()).code(code).name(code)
                .accountQualifier(qualifier).normalBalance(normalBalance)
                .isSummary(isSummary).isPostable(!isSummary).parentValue(parent).displayOrder(displayOrder).build();
        return dv;
    }

    private AccountBalance balance(DimensionValue account, BigDecimal beginning, BigDecimal ptdDr, BigDecimal ptdCr) {
        return AccountBalance.builder()
                .id(UUID.randomUUID())
                .naturalAccount(account)
                .accountCombination(java.util.Map.of())
                .beginningBalance(beginning)
                .periodToDateDr(ptdDr)
                .periodToDateCr(ptdCr)
                .yearToDateDr(ptdDr)
                .yearToDateCr(ptdCr)
                .build();
    }

    private void stubCommon(List<DimensionValue> allAccounts) {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger));
        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(
                ledger.getId(), DimensionType.NATURAL_ACCOUNT)).thenReturn(Optional.of(naturalAcctDim));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsActiveTrueOrderByDisplayOrderAsc(
                naturalAcctDim.getId())).thenReturn(allAccounts);
        lenient().when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        lenient().when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
    }

    @Test
    void testGetHierarchicalTrialBalance_flatAccounts_returnsAllAsRoots() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, false, null, 1);
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, false, null, 2);
        stubCommon(List.of(cash, revenue));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of(
                        balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                        balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"))));

        HierarchicalTrialBalanceResponse response = service.generate(legalEntityId, periodId, null, null);

        assertThat(response.lines()).hasSize(2);
        assertThat(response.lines()).allMatch(l -> l.children().isEmpty() && l.depth() == 0);
    }

    @Test
    void testGetHierarchicalTrialBalance_withHierarchy_rollsUpToSummary() {
        DimensionValue summary = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true, null, 1);
        DimensionValue childA = account("4100", AccountQualifier.REVENUE, NormalBalance.CR, false, summary, 1);
        DimensionValue childB = account("4200", AccountQualifier.REVENUE, NormalBalance.CR, false, summary, 2);
        stubCommon(List.of(summary, childA, childB));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of(
                        balance(childA, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("60.00")),
                        balance(childB, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("40.00"))));

        HierarchicalTrialBalanceResponse response = service.generate(legalEntityId, periodId, null, null);

        assertThat(response.lines()).hasSize(1);
        HierarchicalTrialBalanceLine summaryLine = response.lines().get(0);
        assertThat(summaryLine.accountCode()).isEqualTo("4000");
        assertThat(summaryLine.children()).hasSize(2);
        assertThat(summaryLine.creditBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void testGetHierarchicalTrialBalance_leafBalance_appearsAtCorrectDepth() {
        DimensionValue summary = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true, null, 1);
        DimensionValue child = account("4100", AccountQualifier.REVENUE, NormalBalance.CR, false, summary, 1);
        DimensionValue grandchild = account("4110", AccountQualifier.REVENUE, NormalBalance.CR, false, child, 1);
        stubCommon(List.of(summary, child, grandchild));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of(balance(grandchild, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("25.00"))));

        HierarchicalTrialBalanceResponse response = service.generate(legalEntityId, periodId, null, null);

        HierarchicalTrialBalanceLine summaryLine = response.lines().get(0);
        assertThat(summaryLine.depth()).isEqualTo(0);
        HierarchicalTrialBalanceLine childLine = summaryLine.children().get(0);
        assertThat(childLine.depth()).isEqualTo(1);
        HierarchicalTrialBalanceLine grandchildLine = childLine.children().get(0);
        assertThat(grandchildLine.depth()).isEqualTo(2);
        assertThat(grandchildLine.creditBalance()).isEqualByComparingTo("25.00");
    }

    @Test
    void testGetHierarchicalTrialBalance_summaryBalance_equalsSumOfLeaves() {
        DimensionValue summary = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true, null, 1);
        DimensionValue childA = account("1100", AccountQualifier.ASSET, NormalBalance.DR, false, summary, 1);
        DimensionValue childB = account("1200", AccountQualifier.ASSET, NormalBalance.DR, false, summary, 2);
        stubCommon(List.of(summary, childA, childB));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of(
                        balance(childA, BigDecimal.ZERO, new BigDecimal("70.00"), BigDecimal.ZERO),
                        balance(childB, BigDecimal.ZERO, new BigDecimal("30.00"), BigDecimal.ZERO)));

        HierarchicalTrialBalanceResponse response = service.generate(legalEntityId, periodId, null, null);

        HierarchicalTrialBalanceLine summaryLine = response.lines().get(0);
        BigDecimal sumOfChildren = summaryLine.children().stream()
                .map(HierarchicalTrialBalanceLine::debitBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summaryLine.debitBalance()).isEqualByComparingTo(sumOfChildren);
        assertThat(summaryLine.debitBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void testGetHierarchicalTrialBalance_filteredByUnit_onlyMatchingBalances() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, false, null, 1);
        stubCommon(List.of(cash));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
                        legalEntityId, periodId, "{\"UNIT\":\"CBE-1\"}"))
                .thenReturn(List.of(balance(cash, BigDecimal.ZERO, new BigDecimal("40.00"), BigDecimal.ZERO)));

        HierarchicalTrialBalanceResponse response = service.generate(legalEntityId, periodId, "CBE-1", null);

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).debitBalance()).isEqualByComparingTo("40.00");
    }

    @Test
    void testGetHierarchicalTrialBalance_totalDrEqualsTotalCr_isBalanced() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, false, null, 1);
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, false, null, 2);
        stubCommon(List.of(cash, revenue));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of(
                        balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                        balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"))));

        HierarchicalTrialBalanceResponse response = service.generate(legalEntityId, periodId, null, null);

        assertThat(response.isBalanced()).isTrue();
        assertThat(response.totalDebit()).isEqualByComparingTo(response.totalCredit());
    }

    @Test
    void testGetHierarchicalTrialBalance_noBalances_throws404() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, false, null, 1);
        stubCommon(List.of(cash));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId, null, null))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NO_BALANCES_FOUND");
    }
}
