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
public class BalanceSheetService {

    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final LegalEntityRepository legalEntityRepository;

    public BalanceSheetResponse generate(UUID legalEntityId, UUID periodId) {

        Ledger ledger = legalEntityLedgerRepository
                .findPrimaryLedgerByLegalEntityId(legalEntityId)
                .orElseThrow(() -> new EvyoogException("NO_PRIMARY_LEDGER",
                        "No primary Ledger found.", HttpStatus.NOT_FOUND));

        if (ledger.getFinanceMode() != FinanceMode.THICK) {
            throw new EvyoogException("THICK_MODE_ONLY",
                    "Balance Sheet is only available for THICK finance mode Ledgers. "
                            + "Current mode: " + ledger.getFinanceMode(),
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
                .filter(ab -> isBalanceSheetAccount(ab.getNaturalAccount()))
                .collect(Collectors.toMap(
                        ab -> ab.getNaturalAccount().getId(),
                        ab -> new BalanceTotals(
                                ab.getBeginningBalance(), ab.getPeriodToDateDr(), ab.getPeriodToDateCr()),
                        BalanceTotals::add));

        List<DimensionValue> allBsAccounts = dimensionValueRepository
                .findByFinanceDimension_Ledger_IdAndAccountQualifierIn(
                        ledger.getId(),
                        List.of(AccountQualifier.ASSET, AccountQualifier.LIABILITY, AccountQualifier.EQUITY));

        List<BalanceSheetLineItem> assetItems = buildHierarchy(
                allBsAccounts, balanceByAccountId, AccountQualifier.ASSET, null);
        List<BalanceSheetLineItem> liabilityItems = buildHierarchy(
                allBsAccounts, balanceByAccountId, AccountQualifier.LIABILITY, null);
        List<BalanceSheetLineItem> equityItems = buildHierarchy(
                allBsAccounts, balanceByAccountId, AccountQualifier.EQUITY, null);

        BigDecimal totalAssets = sumEndingBalance(assetItems);
        BigDecimal totalLiabilities = sumEndingBalance(liabilityItems);
        BigDecimal totalEquity = sumEndingBalance(equityItems);
        BigDecimal totalLandE = totalLiabilities.add(totalEquity);

        AccountingPeriod period = accountingPeriodRepository.findById(periodId)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND",
                        "Accounting period not found.", HttpStatus.NOT_FOUND));
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new EvyoogException("LEGAL_ENTITY_NOT_FOUND",
                        "Legal Entity not found.", HttpStatus.NOT_FOUND));

        return BalanceSheetResponse.builder()
                .legalEntityId(legalEntityId)
                .legalEntityName(legalEntity.getName())
                .legalEntityCode(legalEntity.getCode())
                .accountingPeriodId(periodId)
                .periodName(period.getName())
                .fiscalYear(period.getFiscalYear())
                .financeMode(ledger.getFinanceMode().name())
                .generatedAt(LocalDate.now())
                .assetItems(assetItems)
                .liabilityItems(liabilityItems)
                .equityItems(equityItems)
                .totalAssets(totalAssets)
                .totalLiabilities(totalLiabilities)
                .totalEquity(totalEquity)
                .totalLiabilitiesAndEquity(totalLandE)
                .isBalanced(totalAssets.compareTo(totalLandE) == 0)
                .build();
    }

    private List<BalanceSheetLineItem> buildHierarchy(
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
                    List<BalanceSheetLineItem> children = buildHierarchy(
                            allAccounts, balances, qualifier, dv.getId());

                    BalanceTotals bal = balances.get(dv.getId());

                    BigDecimal beginning = BigDecimal.ZERO;
                    BigDecimal ptdDr = BigDecimal.ZERO;
                    BigDecimal ptdCr = BigDecimal.ZERO;

                    if (bal != null) {
                        beginning = bal.beginningBalance();
                        ptdDr = bal.periodToDateDr();
                        ptdCr = bal.periodToDateCr();
                    }

                    BigDecimal rawEnding = beginning.add(ptdDr).subtract(ptdCr);
                    BigDecimal ending = (dv.getNormalBalance() == NormalBalance.CR)
                            ? rawEnding.negate()
                            : rawEnding;

                    if (!children.isEmpty()) {
                        ending = ending.add(sumEndingBalance(children));
                    }

                    return BalanceSheetLineItem.builder()
                            .accountId(dv.getId())
                            .accountCode(dv.getCode())
                            .accountName(dv.getName())
                            .accountQualifier(qualifier.name())
                            .isSummary(dv.isSummary())
                            .isPostable(dv.isPostable())
                            .displayOrder(dv.getDisplayOrder())
                            .beginningBalance(beginning)
                            .periodToDateDr(ptdDr)
                            .periodToDateCr(ptdCr)
                            .endingBalance(ending)
                            .children(children)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private BigDecimal sumEndingBalance(List<BalanceSheetLineItem> items) {
        return items.stream()
                .map(BalanceSheetLineItem::endingBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isBalanceSheetAccount(DimensionValue dv) {
        return dv.getAccountQualifier() == AccountQualifier.ASSET
                || dv.getAccountQualifier() == AccountQualifier.LIABILITY
                || dv.getAccountQualifier() == AccountQualifier.EQUITY;
    }

    private record BalanceTotals(BigDecimal beginningBalance, BigDecimal periodToDateDr, BigDecimal periodToDateCr) {

        BalanceTotals add(BalanceTotals other) {
            return new BalanceTotals(
                    beginningBalance.add(other.beginningBalance),
                    periodToDateDr.add(other.periodToDateDr),
                    periodToDateCr.add(other.periodToDateCr));
        }
    }
}
