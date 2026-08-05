package com.evyoog.gl.reporting.trialbalance.service;

import com.evyoog.gl.common.exception.EvyoogException;
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
import com.evyoog.gl.reporting.trialbalance.dto.SegmentFilters;
import com.evyoog.gl.reporting.trialbalance.dto.TrialBalanceLine;
import com.evyoog.gl.reporting.trialbalance.dto.TrialBalanceResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrialBalanceService {

    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ObjectMapper objectMapper;

    public TrialBalanceResponse generate(UUID legalEntityId, UUID periodId) {
        return generate(legalEntityId, periodId, null, null);
    }

    public TrialBalanceResponse generate(UUID legalEntityId, UUID periodId, String costCentre, String product) {

        Ledger ledger = legalEntityLedgerRepository
                .findPrimaryLedgerByLegalEntityId(legalEntityId)
                .orElseThrow(() -> new EvyoogException("NO_PRIMARY_LEDGER",
                        "No primary Ledger found.", HttpStatus.NOT_FOUND));

        if (ledger.getFinanceMode() == FinanceMode.EVENT_ONLY) {
            throw new EvyoogException("EVENT_ONLY_NOT_SUPPORTED",
                    "Trial Balance is not available for Event-only mode Ledgers.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        boolean hasFilter = isNotBlank(costCentre) || isNotBlank(product);

        List<AccountBalance> balances;
        if (hasFilter) {
            balances = accountBalanceRepository.findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
                    legalEntityId, periodId, toCombinationFilterJson(costCentre, product));
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

        List<TrialBalanceLine> lines = balances.stream()
                .filter(ab -> Boolean.TRUE.equals(ab.getNaturalAccount().isPostable()))
                .filter(this::hasActivity)
                .map(this::toTrialBalanceLine)
                .sorted(Comparator.comparing(TrialBalanceLine::accountCode))
                .collect(Collectors.toList());

        BigDecimal totalDr = lines.stream()
                .map(TrialBalanceLine::debitBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCr = lines.stream()
                .map(TrialBalanceLine::creditBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        AccountingPeriod period = accountingPeriodRepository.findById(periodId)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND",
                        "Accounting period not found.", HttpStatus.NOT_FOUND));
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new EvyoogException("LEGAL_ENTITY_NOT_FOUND",
                        "Legal Entity not found.", HttpStatus.NOT_FOUND));

        return TrialBalanceResponse.builder()
                .legalEntityId(legalEntityId)
                .legalEntityName(legalEntity.getName())
                .legalEntityCode(legalEntity.getCode())
                .accountingPeriodId(periodId)
                .periodName(period.getName())
                .fiscalYear(period.getFiscalYear())
                .financeMode(ledger.getFinanceMode().name())
                .generatedAt(LocalDate.now())
                .lines(lines)
                .totalDebit(totalDr)
                .totalCredit(totalCr)
                .isBalanced(totalDr.compareTo(totalCr) == 0)
                .totalAccounts(lines.size())
                .accountsWithActivity((int) lines.stream()
                        .filter(this::hasActivityLine)
                        .count())
                .segmentFilters(new SegmentFilters(
                        isNotBlank(costCentre) ? costCentre : null,
                        isNotBlank(product) ? product : null))
                .build();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String toCombinationFilterJson(String costCentre, String product) {
        Map<String, String> filter = new LinkedHashMap<>();
        if (isNotBlank(costCentre)) {
            filter.put("COST_CENTRE", costCentre);
        }
        if (isNotBlank(product)) {
            filter.put("PRODUCT", product);
        }
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize segment filter", e);
        }
    }

    private TrialBalanceLine toTrialBalanceLine(AccountBalance ab) {
        BigDecimal ending = ab.getBeginningBalance()
                .add(ab.getPeriodToDateDr())
                .subtract(ab.getPeriodToDateCr());

        NormalBalance normalBalance = ab.getNaturalAccount().getNormalBalance();

        BigDecimal debitBal = (normalBalance == NormalBalance.DR)
                ? ending : BigDecimal.ZERO;
        BigDecimal creditBal = (normalBalance == NormalBalance.CR)
                ? ending.abs() : BigDecimal.ZERO;

        return TrialBalanceLine.builder()
                .accountCode(ab.getNaturalAccount().getCode())
                .accountName(ab.getNaturalAccount().getName())
                .accountQualifier(ab.getNaturalAccount().getAccountQualifier() != null
                        ? ab.getNaturalAccount().getAccountQualifier().name() : null)
                .normalBalance(normalBalance != null ? normalBalance.name() : null)
                .beginningBalance(ab.getBeginningBalance())
                .periodToDateDr(ab.getPeriodToDateDr())
                .periodToDateCr(ab.getPeriodToDateCr())
                .yearToDateDr(ab.getYearToDateDr())
                .yearToDateCr(ab.getYearToDateCr())
                .endingBalance(ending)
                .debitBalance(debitBal)
                .creditBalance(creditBal)
                .build();
    }

    private boolean hasActivity(AccountBalance ab) {
        return ab.getPeriodToDateDr().compareTo(BigDecimal.ZERO) != 0
                || ab.getPeriodToDateCr().compareTo(BigDecimal.ZERO) != 0
                || ab.getBeginningBalance().compareTo(BigDecimal.ZERO) != 0;
    }

    private boolean hasActivityLine(TrialBalanceLine line) {
        return line.periodToDateDr().compareTo(BigDecimal.ZERO) != 0
                || line.periodToDateCr().compareTo(BigDecimal.ZERO) != 0;
    }
}
