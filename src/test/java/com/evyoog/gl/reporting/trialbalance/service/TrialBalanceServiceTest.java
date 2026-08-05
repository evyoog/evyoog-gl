package com.evyoog.gl.reporting.trialbalance.service;

import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.NormalBalance;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.posting.domain.AccountBalance;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.reporting.trialbalance.dto.TrialBalanceResponse;
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
class TrialBalanceServiceTest {

    @Mock private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock private AccountBalanceRepository accountBalanceRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private LegalEntityRepository legalEntityRepository;

    private TrialBalanceService service;

    private UUID legalEntityId;
    private UUID periodId;
    private LegalEntity legalEntity;
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        service = new TrialBalanceService(legalEntityLedgerRepository, accountBalanceRepository,
                accountingPeriodRepository, legalEntityRepository, new ObjectMapper());

        legalEntityId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        legalEntity = LegalEntity.builder().id(legalEntityId).code("LE1").name("Legal Entity 1").build();
        period = AccountingPeriod.builder().id(periodId).name("APR-2025").fiscalYear("2025-26").build();
    }

    private Ledger ledger(FinanceMode mode) {
        return Ledger.builder().id(UUID.randomUUID()).code("LDG").name("Primary Ledger").financeMode(mode).build();
    }

    private DimensionValue account(String code, AccountQualifier qualifier, NormalBalance normalBalance, boolean postable) {
        return DimensionValue.builder().id(UUID.randomUUID()).code(code).name(code)
                .accountQualifier(qualifier).normalBalance(normalBalance)
                .isPostable(postable).isSummary(false).build();
    }

    private AccountBalance balance(DimensionValue account, BigDecimal beginning, BigDecimal ptdDr, BigDecimal ptdCr) {
        return AccountBalance.builder()
                .id(UUID.randomUUID())
                .accountingPeriod(period)
                .naturalAccount(account)
                .accountCombination(java.util.Map.of())
                .beginningBalance(beginning)
                .periodToDateDr(ptdDr)
                .periodToDateCr(ptdCr)
                .yearToDateDr(ptdDr)
                .yearToDateCr(ptdCr)
                .build();
    }

    private void stubHappyPath(List<AccountBalance> balances, FinanceMode mode) {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(mode)));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(balances);
        lenient().when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        lenient().when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
    }

    @Test
    void testGenerate_balancedJournal_isBalancedTrue() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true);
        stubHappyPath(List.of(
                balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"))
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.isBalanced()).isTrue();
        assertThat(response.totalDebit()).isEqualByComparingTo("100.00");
        assertThat(response.totalCredit()).isEqualByComparingTo("100.00");
    }

    @Test
    void testGenerate_unbalancedBalances_isBalancedFalse() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true);
        stubHappyPath(List.of(
                balance(cash, BigDecimal.ZERO, new BigDecimal("150.00"), BigDecimal.ZERO),
                balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"))
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.isBalanced()).isFalse();
    }

    @Test
    void testGenerate_eventOnlyMode_throws422() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.EVENT_ONLY)));

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "EVENT_ONLY_NOT_SUPPORTED");
    }

    @Test
    void testGenerate_noBalances_throws404() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NO_BALANCES_FOUND");
    }

    @Test
    void testGenerate_excludesNonPostableAccounts() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        DimensionValue summary = account("1999", AccountQualifier.ASSET, NormalBalance.DR, false);
        stubHappyPath(List.of(
                balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                balance(summary, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO)
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).accountCode()).isEqualTo("1000");
    }

    @Test
    void testGenerate_excludesZeroBalanceAccounts() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        DimensionValue idle = account("1500", AccountQualifier.ASSET, NormalBalance.DR, true);
        stubHappyPath(List.of(
                balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                balance(idle, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).accountCode()).isEqualTo("1000");
    }

    @Test
    void testGenerate_sortedByAccountCode() {
        DimensionValue b = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true);
        DimensionValue a = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        stubHappyPath(List.of(
                balance(b, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00")),
                balance(a, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO)
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.lines()).extracting(l -> l.accountCode())
                .containsExactly("1000", "4000");
    }

    @Test
    void testDebitBalance_drAccount_positiveEnding() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        stubHappyPath(List.of(
                balance(cash, new BigDecimal("50.00"), new BigDecimal("100.00"), new BigDecimal("20.00"))
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.lines().get(0).debitBalance()).isEqualByComparingTo("130.00");
        assertThat(response.lines().get(0).creditBalance()).isEqualByComparingTo("0");
    }

    @Test
    void testCreditBalance_crAccount_correctValue() {
        DimensionValue payable = account("2000", AccountQualifier.LIABILITY, NormalBalance.CR, true);
        stubHappyPath(List.of(
                balance(payable, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500.00"))
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.lines().get(0).creditBalance()).isEqualByComparingTo("500.00");
        assertThat(response.lines().get(0).debitBalance()).isEqualByComparingTo("0");
    }

    @Test
    void testTotalDr_equalsSum_ofDebitBalances() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        DimensionValue expense = account("5000", AccountQualifier.EXPENSE, NormalBalance.DR, true);
        stubHappyPath(List.of(
                balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                balance(expense, BigDecimal.ZERO, new BigDecimal("50.00"), BigDecimal.ZERO)
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.totalDebit()).isEqualByComparingTo("150.00");
    }

    @Test
    void testTotalCr_equalsSum_ofCreditBalances() {
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true);
        DimensionValue payable = account("2000", AccountQualifier.LIABILITY, NormalBalance.CR, true);
        stubHappyPath(List.of(
                balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00")),
                balance(payable, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50.00"))
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.totalCredit()).isEqualByComparingTo("150.00");
    }

    @Test
    void testTrialBalance_noCostCentreFilter_returnsAllLines() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true);
        stubHappyPath(List.of(
                balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"))
        ), FinanceMode.THICK);

        TrialBalanceResponse response = service.generate(legalEntityId, periodId, null, null);

        assertThat(response.lines()).hasSize(2);
        assertThat(response.segmentFilters().costCentre()).isNull();
        assertThat(response.segmentFilters().product()).isNull();
    }

    @Test
    void testTrialBalance_withCostCentreFilter_returnsFilteredLines() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        DimensionValue cash = account("1000", AccountQualifier.ASSET, NormalBalance.DR, true);
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
                        legalEntityId, periodId, "{\"COST_CENTRE\":\"CC-MFG\"}"))
                .thenReturn(List.of(balance(cash, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO)));
        when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));

        TrialBalanceResponse response = service.generate(legalEntityId, periodId, "CC-MFG", null);

        assertThat(response.lines()).hasSize(1);
        assertThat(response.segmentFilters().costCentre()).isEqualTo("CC-MFG");
        assertThat(response.segmentFilters().product()).isNull();
    }

    @Test
    void testTrialBalance_withProductFilter_returnsFilteredLines() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true);
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
                        legalEntityId, periodId, "{\"PRODUCT\":\"GATE-VLV\"}"))
                .thenReturn(List.of(balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100.00"))));
        when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));

        TrialBalanceResponse response = service.generate(legalEntityId, periodId, null, "GATE-VLV");

        assertThat(response.lines()).hasSize(1);
        assertThat(response.segmentFilters().product()).isEqualTo("GATE-VLV");
    }

    @Test
    void testTrialBalance_withBothFilters_returnsIntersection() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        DimensionValue revenue = account("4000", AccountQualifier.REVENUE, NormalBalance.CR, true);
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
                        legalEntityId, periodId, "{\"COST_CENTRE\":\"CC-SAL\",\"PRODUCT\":\"GATE-VLV\"}"))
                .thenReturn(List.of(balance(revenue, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50.00"))));
        when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));

        TrialBalanceResponse response = service.generate(legalEntityId, periodId, "CC-SAL", "GATE-VLV");

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).creditBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    void testTrialBalance_invalidCostCentre_returnsEmptyLines() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
                        legalEntityId, periodId, "{\"COST_CENTRE\":\"NO-SUCH-CC\"}"))
                .thenReturn(List.of());
        when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));

        TrialBalanceResponse response = service.generate(legalEntityId, periodId, "NO-SUCH-CC", null);

        assertThat(response.lines()).isEmpty();
        assertThat(response.totalDebit()).isEqualByComparingTo("0");
        assertThat(response.totalCredit()).isEqualByComparingTo("0");
        assertThat(response.isBalanced()).isTrue();
    }
}
