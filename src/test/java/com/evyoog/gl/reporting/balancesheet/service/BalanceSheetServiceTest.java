package com.evyoog.gl.reporting.balancesheet.service;

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
import com.evyoog.gl.reporting.balancesheet.dto.BalanceSheetLineItem;
import com.evyoog.gl.reporting.balancesheet.dto.BalanceSheetResponse;
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
class BalanceSheetServiceTest {

    @Mock private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock private AccountBalanceRepository accountBalanceRepository;
    @Mock private DimensionValueRepository dimensionValueRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private LegalEntityRepository legalEntityRepository;

    private BalanceSheetService service;

    private UUID legalEntityId;
    private UUID periodId;
    private UUID ledgerId;
    private LegalEntity legalEntity;
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        service = new BalanceSheetService(legalEntityLedgerRepository, accountBalanceRepository,
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
                        qualifier == AccountQualifier.ASSET ? NormalBalance.DR : NormalBalance.CR)
                .parentValue(parent).isSummary(summary).isPostable(!summary).displayOrder(0).build();
    }

    private DimensionValue pnlAccount(String code, AccountQualifier qualifier) {
        return DimensionValue.builder().id(UUID.randomUUID()).code(code).name(code)
                .accountQualifier(qualifier).normalBalance(
                        qualifier == AccountQualifier.REVENUE ? NormalBalance.CR : NormalBalance.DR)
                .isSummary(false).isPostable(true).displayOrder(0).build();
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

    private void stubHappyPath(List<AccountBalance> balances, List<DimensionValue> allAccounts, FinanceMode mode) {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(mode)));
        when(accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(balances);
        lenient().when(dimensionValueRepository.findByFinanceDimension_Ledger_IdAndAccountQualifierIn(
                        ledgerId, List.of(AccountQualifier.ASSET, AccountQualifier.LIABILITY, AccountQualifier.EQUITY)))
                .thenReturn(allAccounts);
        lenient().when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        lenient().when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
    }

    @Test
    void testGenerate_assetsEqualLiabilitiesPlusEquity_isBalancedTrue() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, null, false);
        DimensionValue payable = account("2000", AccountQualifier.LIABILITY, null, false);
        DimensionValue equity = account("3000", AccountQualifier.EQUITY, null, false);
        stubHappyPath(
                List.of(balance(cash, BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO),
                        balance(payable, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("400.00")),
                        balance(equity, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("600.00"))),
                List.of(cash, payable, equity), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.totalAssets()).isEqualByComparingTo("1000.00");
        assertThat(response.totalLiabilitiesAndEquity()).isEqualByComparingTo("1000.00");
        assertThat(response.isBalanced()).isTrue();
    }

    @Test
    void testGenerate_thickModeOnly_thinThrows422() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THIN)));

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "THICK_MODE_ONLY");
    }

    @Test
    void testGenerate_thickModeOnly_eventOnlyThrows422() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.EVENT_ONLY)));

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "THICK_MODE_ONLY");
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
    void testGenerate_assetEndingBalance_beginningPlusDrMinusCr() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, null, false);
        stubHappyPath(
                List.of(balance(cash, new BigDecimal("500.00"), new BigDecimal("300.00"), new BigDecimal("100.00"))),
                List.of(cash), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.assetItems().get(0).endingBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    void testGenerate_hierarchyRollup_summaryIncludesChildren() {
        DimensionValue assetSummary = account("1000", AccountQualifier.ASSET, null, true);
        DimensionValue cash = account("1100", AccountQualifier.ASSET, assetSummary, false);
        DimensionValue bank = account("1200", AccountQualifier.ASSET, assetSummary, false);
        stubHappyPath(
                List.of(balance(cash, BigDecimal.ZERO, new BigDecimal("600.00"), BigDecimal.ZERO),
                        balance(bank, BigDecimal.ZERO, new BigDecimal("300.00"), BigDecimal.ZERO)),
                List.of(assetSummary, cash, bank), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.assetItems()).hasSize(1);
        BalanceSheetLineItem summary = response.assetItems().get(0);
        assertThat(summary.accountCode()).isEqualTo("1000");
        assertThat(summary.children()).hasSize(2);
        assertThat(summary.endingBalance()).isEqualByComparingTo("900.00");
        assertThat(response.totalAssets()).isEqualByComparingTo("900.00");
    }

    @Test
    void testGenerate_totalLiabilitiesAndEquity_sumOfBoth() {
        DimensionValue payable = account("2000", AccountQualifier.LIABILITY, null, false);
        DimensionValue equity = account("3000", AccountQualifier.EQUITY, null, false);
        stubHappyPath(
                List.of(balance(payable, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("400.00")),
                        balance(equity, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("600.00"))),
                List.of(payable, equity), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.totalLiabilities()).isEqualByComparingTo("400.00");
        assertThat(response.totalEquity()).isEqualByComparingTo("600.00");
        assertThat(response.totalLiabilitiesAndEquity()).isEqualByComparingTo("1000.00");
    }

    @Test
    void testGenerate_assetsEqualLiabilitiesPlusEquityPlusNetIncome_isBalancedTrue() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, null, false);
        DimensionValue payable = account("2000", AccountQualifier.LIABILITY, null, false);
        DimensionValue equity = account("3000", AccountQualifier.EQUITY, null, false);
        DimensionValue sales = pnlAccount("4100", AccountQualifier.REVENUE);
        DimensionValue expense = pnlAccount("5100", AccountQualifier.EXPENSE);
        stubHappyPath(
                List.of(
                        balance(cash, BigDecimal.ZERO, new BigDecimal("1600.00"), BigDecimal.ZERO),
                        balance(payable, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("400.00")),
                        balance(equity, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("600.00")),
                        balance(sales, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("700.00")),
                        balance(expense, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO)),
                List.of(cash, payable, equity), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.netIncome()).isEqualByComparingTo("600.00");
        assertThat(response.totalAssets()).isEqualByComparingTo("1600.00");
        assertThat(response.totalLiabilitiesAndEquity()).isEqualByComparingTo("1600.00");
        assertThat(response.isBalanced()).isTrue();
    }

    @Test
    void testGenerate_netIncomeLine_appearsInEquitySection() {
        DimensionValue sales = pnlAccount("4100", AccountQualifier.REVENUE);
        DimensionValue expense = pnlAccount("5100", AccountQualifier.EXPENSE);
        stubHappyPath(
                List.of(
                        balance(sales, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500.00")),
                        balance(expense, BigDecimal.ZERO, new BigDecimal("200.00"), BigDecimal.ZERO)),
                List.of(), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.equityItems())
                .anySatisfy(item -> {
                    assertThat(item.accountCode()).isEqualTo("NET-INCOME");
                    assertThat(item.endingBalance()).isEqualByComparingTo("300.00");
                });
    }

    @Test
    void testGenerate_multipleCombinationsSameAccount_aggregatesIntoOneLine() {
        DimensionValue bank = account("1210", AccountQualifier.ASSET, null, false);
        stubHappyPath(
                List.of(balance(bank, BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO),
                        balance(bank, BigDecimal.ZERO, new BigDecimal("250.00"), BigDecimal.ZERO),
                        balance(bank, BigDecimal.ZERO, new BigDecimal("650.00"), BigDecimal.ZERO),
                        balance(bank, BigDecimal.ZERO, new BigDecimal("0.00"), new BigDecimal("50.00"))),
                List.of(bank), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.assetItems()).hasSize(1);
        assertThat(response.assetItems().get(0).endingBalance()).isEqualByComparingTo("950.00");
        assertThat(response.totalAssets()).isEqualByComparingTo("950.00");
    }

    @Test
    void testGenerate_isProfitable_whenAssetsExceedLiabilities() {
        DimensionValue cash = account("1000", AccountQualifier.ASSET, null, false);
        DimensionValue payable = account("2000", AccountQualifier.LIABILITY, null, false);
        DimensionValue equity = account("3000", AccountQualifier.EQUITY, null, false);
        stubHappyPath(
                List.of(balance(cash, BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO),
                        balance(payable, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300.00")),
                        balance(equity, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("700.00"))),
                List.of(cash, payable, equity), FinanceMode.THICK);

        BalanceSheetResponse response = service.generate(legalEntityId, periodId);

        assertThat(response.totalAssets()).isEqualByComparingTo("1000.00");
        assertThat(response.isBalanced()).isTrue();
    }
}
