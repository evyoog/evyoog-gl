package com.evyoog.gl.reporting.pnl.service;

import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionValue;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProfitAndLossService {

    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final LegalEntityRepository legalEntityRepository;

    public ProfitAndLossResponse generate(UUID legalEntityId, UUID periodId) {

        Ledger ledger = legalEntityLedgerRepository
                .findPrimaryLedgerByLegalEntityId(legalEntityId)
                .orElseThrow(() -> new EvyoogException("NO_PRIMARY_LEDGER",
                        "No primary Ledger found.", HttpStatus.NOT_FOUND));

        if (ledger.getFinanceMode() == FinanceMode.EVENT_ONLY) {
            throw new EvyoogException("EVENT_ONLY_NOT_SUPPORTED",
                    "Profit and Loss is not available for Event-only mode Ledgers.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        List<AccountBalance> balances = accountBalanceRepository
                .findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId);

        if (balances.isEmpty()) {
            throw new EvyoogException("NO_BALANCES_FOUND",
                    "No account balances found. Post some journal entries first.",
                    HttpStatus.NOT_FOUND);
        }

        Map<UUID, BalanceTotals> balanceByAccountId = balances.stream()
                .filter(ab -> isRevenueOrExpense(ab.getNaturalAccount()))
                .collect(Collectors.toMap(
                        ab -> ab.getNaturalAccount().getId(),
                        ab -> new BalanceTotals(
                                ab.getPeriodToDateDr(), ab.getPeriodToDateCr(),
                                ab.getYearToDateDr(), ab.getYearToDateCr()),
                        BalanceTotals::add));

        if (balanceByAccountId.isEmpty()) {
            throw new EvyoogException("NO_PNL_BALANCES",
                    "No Revenue or Expense account balances found for this period.",
                    HttpStatus.NOT_FOUND);
        }

        List<DimensionValue> allPnlAccounts = dimensionValueRepository
                .findByFinanceDimension_Ledger_IdAndAccountQualifierIn(
                        ledger.getId(),
                        List.of(AccountQualifier.REVENUE, AccountQualifier.EXPENSE));

        List<PnlLineItem> revenueItems = buildHierarchy(
                allPnlAccounts, balanceByAccountId, AccountQualifier.REVENUE, null);

        List<PnlLineItem> expenseItems = buildHierarchy(
                allPnlAccounts, balanceByAccountId, AccountQualifier.EXPENSE, null);

        BigDecimal totalRevenue = sumNetAmount(revenueItems);
        BigDecimal totalExpenses = sumNetAmount(expenseItems);
        BigDecimal netIncome = totalRevenue.subtract(totalExpenses);

        AccountingPeriod period = accountingPeriodRepository.findById(periodId)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND",
                        "Accounting period not found.", HttpStatus.NOT_FOUND));
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new EvyoogException("LEGAL_ENTITY_NOT_FOUND",
                        "Legal Entity not found.", HttpStatus.NOT_FOUND));

        return ProfitAndLossResponse.builder()
                .legalEntityId(legalEntityId)
                .legalEntityName(legalEntity.getName())
                .legalEntityCode(legalEntity.getCode())
                .accountingPeriodId(periodId)
                .periodName(period.getName())
                .fiscalYear(period.getFiscalYear())
                .financeMode(ledger.getFinanceMode().name())
                .generatedAt(LocalDate.now())
                .revenueItems(revenueItems)
                .totalRevenue(totalRevenue)
                .expenseItems(expenseItems)
                .totalExpenses(totalExpenses)
                .grossProfit(netIncome)
                .netIncome(netIncome)
                .isProfitable(netIncome.compareTo(BigDecimal.ZERO) > 0)
                .build();
    }

    private List<PnlLineItem> buildHierarchy(
            List<DimensionValue> allAccounts,
            Map<UUID, BalanceTotals> balances,
            AccountQualifier qualifier,
            UUID parentId) {

        return allAccounts.stream()
                .filter(dv -> dv.getAccountQualifier() == qualifier)
                .filter(dv -> parentId == null
                        ? dv.getParentValue() == null
                        : dv.getParentValue() != null && parentId.equals(dv.getParentValue().getId()))
                .sorted(Comparator.comparing(DimensionValue::getDisplayOrder)
                        .thenComparing(DimensionValue::getCode))
                .map(dv -> {
                    List<PnlLineItem> children = buildHierarchy(
                            allAccounts, balances, qualifier, dv.getId());

                    BalanceTotals bal = balances.get(dv.getId());

                    BigDecimal ptdDr = BigDecimal.ZERO;
                    BigDecimal ptdCr = BigDecimal.ZERO;
                    BigDecimal ytdDr = BigDecimal.ZERO;
                    BigDecimal ytdCr = BigDecimal.ZERO;

                    if (bal != null) {
                        ptdDr = bal.periodToDateDr();
                        ptdCr = bal.periodToDateCr();
                        ytdDr = bal.yearToDateDr();
                        ytdCr = bal.yearToDateCr();
                    }

                    BigDecimal netAmount = (qualifier == AccountQualifier.REVENUE)
                            ? ptdCr.subtract(ptdDr)
                            : ptdDr.subtract(ptdCr);

                    if (!children.isEmpty()) {
                        netAmount = netAmount.add(sumNetAmount(children));
                    }

                    return PnlLineItem.builder()
                            .accountId(dv.getId())
                            .accountCode(dv.getCode())
                            .accountName(dv.getName())
                            .accountQualifier(qualifier.name())
                            .isSummary(dv.isSummary())
                            .isPostable(dv.isPostable())
                            .displayOrder(dv.getDisplayOrder())
                            .periodToDateDr(ptdDr)
                            .periodToDateCr(ptdCr)
                            .ytdDr(ytdDr)
                            .ytdCr(ytdCr)
                            .netAmount(netAmount)
                            .children(children)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private BigDecimal sumNetAmount(List<PnlLineItem> items) {
        return items.stream()
                .map(PnlLineItem::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isRevenueOrExpense(DimensionValue dv) {
        return dv.getAccountQualifier() == AccountQualifier.REVENUE
                || dv.getAccountQualifier() == AccountQualifier.EXPENSE;
    }

    private record BalanceTotals(
            BigDecimal periodToDateDr, BigDecimal periodToDateCr, BigDecimal yearToDateDr, BigDecimal yearToDateCr) {

        BalanceTotals add(BalanceTotals other) {
            return new BalanceTotals(
                    periodToDateDr.add(other.periodToDateDr),
                    periodToDateCr.add(other.periodToDateCr),
                    yearToDateDr.add(other.yearToDateDr),
                    yearToDateCr.add(other.yearToDateCr));
        }
    }
}
