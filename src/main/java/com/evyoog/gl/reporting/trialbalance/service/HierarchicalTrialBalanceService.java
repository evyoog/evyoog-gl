package com.evyoog.gl.reporting.trialbalance.service;

import com.evyoog.gl.common.exception.EvyoogException;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HierarchicalTrialBalanceService {

    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final FinanceDimensionRepository financeDimensionRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ObjectMapper objectMapper;

    public HierarchicalTrialBalanceResponse generate(
            UUID legalEntityId, UUID periodId, String unitCode, String costCentreCode) {

        Ledger ledger = legalEntityLedgerRepository
                .findPrimaryLedgerByLegalEntityId(legalEntityId)
                .orElseThrow(() -> new EvyoogException("NO_PRIMARY_LEDGER",
                        "No primary Ledger found.", HttpStatus.NOT_FOUND));

        if (ledger.getFinanceMode() == FinanceMode.EVENT_ONLY) {
            throw new EvyoogException("EVENT_ONLY_NOT_SUPPORTED",
                    "Trial Balance is not available for Event-only mode Ledgers.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        FinanceDimension naturalAcctDim = financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledger.getId(), DimensionType.NATURAL_ACCOUNT)
                .orElseThrow(() -> new EvyoogException("NATURAL_ACCOUNT_DIMENSION_NOT_FOUND",
                        "No active Natural Account dimension found for this Ledger.", HttpStatus.NOT_FOUND));

        List<DimensionValue> allAccounts = dimensionValueRepository
                .findByFinanceDimensionIdAndIsActiveTrueOrderByDisplayOrderAsc(naturalAcctDim.getId());

        boolean hasFilter = isNotBlank(unitCode) || isNotBlank(costCentreCode);

        List<AccountBalance> balances;
        if (hasFilter) {
            balances = accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
                    legalEntityId, periodId, toCombinationFilterJson(unitCode, costCentreCode));
        } else {
            balances = accountBalanceRepository
                    .findByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId);

            if (balances.isEmpty()) {
                throw new EvyoogException("NO_BALANCES_FOUND",
                        "No account balances found for this Legal Entity and Period. "
                                + "Post some journal entries first.",
                        HttpStatus.NOT_FOUND);
            }
        }

        Map<UUID, BalanceTotals> balanceMap = buildBalanceMap(balances);

        List<DimensionValue> roots = allAccounts.stream()
                .filter(dv -> dv.getParentValue() == null)
                .sorted(Comparator.comparing(DimensionValue::getDisplayOrder).thenComparing(DimensionValue::getCode))
                .collect(Collectors.toList());

        List<HierarchicalTrialBalanceLine> rootLines = roots.stream()
                .map(root -> buildLine(root, allAccounts, balanceMap, 0))
                .collect(Collectors.toList());

        List<HierarchicalTrialBalanceLine> leaves = flattenLeaves(rootLines);
        BigDecimal totalDr = leaves.stream()
                .map(HierarchicalTrialBalanceLine::debitBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCr = leaves.stream()
                .map(HierarchicalTrialBalanceLine::creditBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<HierarchicalTrialBalanceLine> allLines = flattenAll(rootLines);

        AccountingPeriod period = accountingPeriodRepository.findById(periodId)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND",
                        "Accounting period not found.", HttpStatus.NOT_FOUND));
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new EvyoogException("LEGAL_ENTITY_NOT_FOUND",
                        "Legal Entity not found.", HttpStatus.NOT_FOUND));

        return HierarchicalTrialBalanceResponse.builder()
                .legalEntityId(legalEntityId)
                .legalEntityName(legalEntity.getName())
                .accountingPeriodId(periodId)
                .periodName(period.getName())
                .fiscalYear(period.getFiscalYear())
                .generatedAt(LocalDate.now().toString())
                .lines(rootLines)
                .totalDebit(totalDr)
                .totalCredit(totalCr)
                .isBalanced(totalDr.compareTo(totalCr) == 0)
                .totalAccounts(allLines.size())
                .accountsWithActivity((int) leaves.stream().filter(this::hasActivity).count())
                .build();
    }

    private HierarchicalTrialBalanceLine buildLine(
            DimensionValue account,
            List<DimensionValue> allAccounts,
            Map<UUID, BalanceTotals> balanceMap,
            int depth) {

        List<DimensionValue> children = allAccounts.stream()
                .filter(dv -> dv.getParentValue() != null && account.getId().equals(dv.getParentValue().getId()))
                .sorted(Comparator.comparing(DimensionValue::getDisplayOrder).thenComparing(DimensionValue::getCode))
                .collect(Collectors.toList());

        List<HierarchicalTrialBalanceLine> childLines = children.stream()
                .map(child -> buildLine(child, allAccounts, balanceMap, depth + 1))
                .collect(Collectors.toList());

        BalanceTotals totals = childLines.isEmpty()
                ? balanceMap.getOrDefault(account.getId(), BalanceTotals.ZERO)
                : childLines.stream()
                        .map(line -> new BalanceTotals(
                                line.beginningBalance(), line.periodToDateDr(), line.periodToDateCr(),
                                line.yearToDateDr(), line.yearToDateCr()))
                        .reduce(BalanceTotals.ZERO, BalanceTotals::add);

        NormalBalance normalBalance = account.getNormalBalance();
        BigDecimal ending = totals.beginningBalance().add(totals.ptdDr()).subtract(totals.ptdCr());
        BigDecimal debitBal = (normalBalance == NormalBalance.DR) ? ending : BigDecimal.ZERO;
        BigDecimal creditBal = (normalBalance == NormalBalance.CR) ? ending.abs() : BigDecimal.ZERO;

        return HierarchicalTrialBalanceLine.builder()
                .accountId(account.getId())
                .accountCode(account.getCode())
                .accountName(account.getName())
                .accountQualifier(account.getAccountQualifier() != null ? account.getAccountQualifier().name() : null)
                .normalBalance(normalBalance != null ? normalBalance.name() : null)
                .isSummary(account.isSummary())
                .isPostable(account.isPostable())
                .depth(depth)
                .beginningBalance(totals.beginningBalance())
                .periodToDateDr(totals.ptdDr())
                .periodToDateCr(totals.ptdCr())
                .yearToDateDr(totals.ytdDr())
                .yearToDateCr(totals.ytdCr())
                .endingBalance(ending)
                .debitBalance(debitBal)
                .creditBalance(creditBal)
                .children(childLines)
                .build();
    }

    private Map<UUID, BalanceTotals> buildBalanceMap(List<AccountBalance> balances) {
        Map<UUID, BalanceTotals> map = new HashMap<>();
        for (AccountBalance ab : balances) {
            UUID accountId = ab.getNaturalAccount().getId();
            BalanceTotals totals = new BalanceTotals(
                    ab.getBeginningBalance(), ab.getPeriodToDateDr(), ab.getPeriodToDateCr(),
                    ab.getYearToDateDr(), ab.getYearToDateCr());
            map.merge(accountId, totals, BalanceTotals::add);
        }
        return map;
    }

    private List<HierarchicalTrialBalanceLine> flattenLeaves(List<HierarchicalTrialBalanceLine> lines) {
        List<HierarchicalTrialBalanceLine> result = new ArrayList<>();
        for (HierarchicalTrialBalanceLine line : lines) {
            if (line.children().isEmpty()) {
                result.add(line);
            } else {
                result.addAll(flattenLeaves(line.children()));
            }
        }
        return result;
    }

    private List<HierarchicalTrialBalanceLine> flattenAll(List<HierarchicalTrialBalanceLine> lines) {
        List<HierarchicalTrialBalanceLine> result = new ArrayList<>();
        for (HierarchicalTrialBalanceLine line : lines) {
            result.add(line);
            result.addAll(flattenAll(line.children()));
        }
        return result;
    }

    private boolean hasActivity(HierarchicalTrialBalanceLine line) {
        return line.periodToDateDr().compareTo(BigDecimal.ZERO) != 0
                || line.periodToDateCr().compareTo(BigDecimal.ZERO) != 0;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String toCombinationFilterJson(String unitCode, String costCentreCode) {
        Map<String, String> filter = new LinkedHashMap<>();
        if (isNotBlank(unitCode)) {
            filter.put("UNIT", unitCode);
        }
        if (isNotBlank(costCentreCode)) {
            filter.put("COST_CENTRE", costCentreCode);
        }
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize segment filter", e);
        }
    }

    private record BalanceTotals(
            BigDecimal beginningBalance,
            BigDecimal ptdDr,
            BigDecimal ptdCr,
            BigDecimal ytdDr,
            BigDecimal ytdCr) {

        static final BalanceTotals ZERO = new BalanceTotals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        BalanceTotals add(BalanceTotals other) {
            return new BalanceTotals(
                    beginningBalance.add(other.beginningBalance),
                    ptdDr.add(other.ptdDr),
                    ptdCr.add(other.ptdCr),
                    ytdDr.add(other.ytdDr),
                    ytdCr.add(other.ytdCr));
        }
    }
}
