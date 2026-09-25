package com.evyoog.gl.reporting.pnl.service;

import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.NormalBalance;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.posting.domain.AccountBalance;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.reporting.pnl.dto.PnlLineItem;
import com.evyoog.gl.reporting.pnl.dto.ProfitAndLossResponse;
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
class ProfitAndLossServiceTest {

    @Mock private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock private AccountBalanceRepository accountBalanceRepository;
    @Mock private DimensionValueRepository dimensionValueRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private LegalEntityRepository legalEntityRepository;

    private ProfitAndLossService service;

    private UUID legalEntityId;
    private UUID periodId;
    private UUID ledgerId;
    private LegalEntity legalEntity;
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        service = new ProfitAndLossService(legalEntityLedgerRepository, accountBalanceRepository,
                dimensionValueRepository, accountingPeriodRepository, legalEntityRepository);

        legalEntityId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        ledgerId = UUID.randomUUID();
        legalEntity = LegalEntity.builder().id(legalEntityId).code("LE1").name("Legal Entity 1").build();
        period = AccountingPeriod.builder().id(periodId).name("APR-2025").fiscalYear("2025-26").build();
    }

    private Ledger ledger(FinanceMode mode) {
        return Ledger.builder().id(ledgerId).code("LDG").name("Primary Ledger").financeMode(mode).build();
    }

    private DimensionValue account(String code, AccountQualifier qualifier, DimensionValue parent, boolean summary) {
        return DimensionValue.builder().id(UUID.randomUUID()).code(code).name(code)
                .accountQualifier(qualifier).normalBalance(
                        qualifier == AccountQualifier.REVENUE ? NormalBalance.CR : NormalBalance.DR)
                .parentValue(parent).isSummary(summary).isPostable(!summary).displayOrder(0).build();
    }

    private AccountBalance balance(DimensionValue account, BigDecimal ptdDr, BigDecimal ptdCr) {
        return AccountBalance.builder()
                .id(UUID.randomUUID())
                .accountingPeriod(period)
                .naturalAccount(account)
                .accountCombination(java.util.Map.of())
                .beginningBalance(BigDecimal.ZERO)
                .periodToDateDr(ptdDr)
                .periodToDateCr(ptdCr)
                .yearToDateDr(ptdDr)
                .yearToDateCr(ptdCr)
                .build();
    }

    private void stubHappyPath(List<AccountBalance> balances, List<DimensionValue> allAccounts, FinanceMode mode) {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(mode)));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(balances);
        lenient().when(dimensionValueRepository.findByFinanceDimension_Ledger_IdAndAccountQualifierIn(
                        ledgerId, List.of(AccountQualifier.REVENUE, AccountQualifier.EXPENSE)))
                .thenReturn(allAccounts);
        lenient().when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        lenient().when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
    }

    @Test
    void testGenerate_revenueMinusExpense_netIncomeCorrect() {
        DimensionValue sales = account("4100", AccountQualifier.REVENUE, null, false);
        DimensionValue rawMaterial = account("5100", AccountQualifier.EXPENSE, null, false);
        stubHappyPath(
                List.of(balance(sales, BigDecimal.ZERO, new BigDecimal("1000.00")),
                        balance(rawMaterial, new BigDecimal("400.00"), BigDecimal.ZERO)),
                List.of(sales, rawMaterial), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.totalRevenue()).isEqualByComparingTo("1000.00");
        assertThat(response.totalExpenses()).isEqualByComparingTo("400.00");
        assertThat(response.netIncome()).isEqualByComparingTo("600.00");
        assertThat(response.grossProfit()).isEqualByComparingTo("600.00");
    }

    @Test
    void testGenerate_hierarchyRollup_summaryIncludesChildren() {
        DimensionValue revenueSummary = account("4000", AccountQualifier.REVENUE, null, true);
        DimensionValue domestic = account("4100", AccountQualifier.REVENUE, revenueSummary, false);
        DimensionValue export = account("4200", AccountQualifier.REVENUE, revenueSummary, false);
        stubHappyPath(
                List.of(balance(domestic, BigDecimal.ZERO, new BigDecimal("600.00")),
                        balance(export, BigDecimal.ZERO, new BigDecimal("300.00"))),
                List.of(revenueSummary, domestic, export), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.revenueItems()).hasSize(1);
        PnlLineItem summary = response.revenueItems().get(0);
        assertThat(summary.accountCode()).isEqualTo("4000");
        assertThat(summary.children()).hasSize(2);
        assertThat(summary.netAmount()).isEqualByComparingTo("900.00");
        assertThat(response.totalRevenue()).isEqualByComparingTo("900.00");
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
    void testGenerate_noPnlBalances_throws404() {
        DimensionValue cash = DimensionValue.builder().id(UUID.randomUUID()).code("1000").name("Cash")
                .accountQualifier(AccountQualifier.ASSET).normalBalance(NormalBalance.DR)
                .isPostable(true).isSummary(false).build();
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(List.of(balance(cash, new BigDecimal("100.00"), BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NO_PNL_BALANCES");
    }

    @Test
    void testGenerate_isProfitable_true_whenRevenueExceedsExpense() {
        DimensionValue sales = account("4100", AccountQualifier.REVENUE, null, false);
        DimensionValue expense = account("5100", AccountQualifier.EXPENSE, null, false);
        stubHappyPath(
                List.of(balance(sales, BigDecimal.ZERO, new BigDecimal("1000.00")),
                        balance(expense, new BigDecimal("400.00"), BigDecimal.ZERO)),
                List.of(sales, expense), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.isProfitable()).isTrue();
    }

    @Test
    void testGenerate_isProfitable_false_whenExpenseExceedsRevenue() {
        DimensionValue sales = account("4100", AccountQualifier.REVENUE, null, false);
        DimensionValue expense = account("5100", AccountQualifier.EXPENSE, null, false);
        stubHappyPath(
                List.of(balance(sales, BigDecimal.ZERO, new BigDecimal("100.00")),
                        balance(expense, new BigDecimal("400.00"), BigDecimal.ZERO)),
                List.of(sales, expense), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.isProfitable()).isFalse();
    }

    @Test
    void testGenerate_revenueNetAmount_creditMinusDebit() {
        DimensionValue sales = account("4100", AccountQualifier.REVENUE, null, false);
        stubHappyPath(
                List.of(balance(sales, new BigDecimal("50.00"), new BigDecimal("300.00"))),
                List.of(sales), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.revenueItems().get(0).netAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void testGenerate_expenseNetAmount_debitMinusCredit() {
        DimensionValue expense = account("5100", AccountQualifier.EXPENSE, null, false);
        stubHappyPath(
                List.of(balance(expense, new BigDecimal("300.00"), new BigDecimal("50.00"))),
                List.of(expense), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.expenseItems().get(0).netAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void testGenerate_multipleCombinationsSameAccount_aggregatesIntoOneLine() {
        DimensionValue sales = account("4100", AccountQualifier.REVENUE, null, false);
        stubHappyPath(
                List.of(balance(sales, BigDecimal.ZERO, new BigDecimal("100.00")),
                        balance(sales, BigDecimal.ZERO, new BigDecimal("250.00")),
                        balance(sales, BigDecimal.ZERO, new BigDecimal("650.00"))),
                List.of(sales), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.revenueItems()).hasSize(1);
        assertThat(response.revenueItems().get(0).netAmount()).isEqualByComparingTo("1000.00");
        assertThat(response.totalRevenue()).isEqualByComparingTo("1000.00");
    }

    @Test
    void testGenerate_sortedByDisplayOrderThenCode() {
        DimensionValue export = account("4200", AccountQualifier.REVENUE, null, false);
        DimensionValue domestic = account("4100", AccountQualifier.REVENUE, null, false);
        stubHappyPath(
                List.of(balance(export, BigDecimal.ZERO, new BigDecimal("100.00")),
                        balance(domestic, BigDecimal.ZERO, new BigDecimal("200.00"))),
                List.of(export, domestic), FinanceMode.THICK);

        ProfitAndLossResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.revenueItems()).extracting(PnlLineItem::accountCode)
                .containsExactly("4100", "4200");
    }
}
