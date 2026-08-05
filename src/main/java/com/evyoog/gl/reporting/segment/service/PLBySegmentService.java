package com.evyoog.gl.reporting.segment.service;

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
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.posting.repository.PLSegmentPivotRow;
import com.evyoog.gl.reporting.segment.dto.PLBySegmentLine;
import com.evyoog.gl.reporting.segment.dto.PLBySegmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PLBySegmentService {

    private static final String TOTAL_KEY = "total";

    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final FinanceDimensionRepository financeDimensionRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final LegalEntityRepository legalEntityRepository;

    public PLBySegmentResponse generate(UUID legalEntityId, UUID periodId, String segmentType,
                                         boolean includeZeroBalances) {

        DimensionType type = parseSegmentType(segmentType);

        Ledger ledger = legalEntityLedgerRepository
                .findPrimaryLedgerByLegalEntityId(legalEntityId)
                .orElseThrow(() -> new EvyoogException("NO_PRIMARY_LEDGER",
                        "No primary Ledger found.", HttpStatus.NOT_FOUND));

        if (ledger.getFinanceMode() == FinanceMode.EVENT_ONLY) {
            throw new EvyoogException("EVENT_ONLY_NOT_SUPPORTED",
                    "P&L by Segment is not available for Event-only mode Ledgers.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (!accountBalanceRepository.existsByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId)) {
            throw new EvyoogException("NO_BALANCES_FOUND",
                    "No account balances found for this Legal Entity and Period. "
                            + "Post some journal entries first.",
                    HttpStatus.NOT_FOUND);
        }

        FinanceDimension dimension = financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledger.getId(), type)
                .orElseThrow(() -> new EvyoogException("SEGMENT_DIMENSION_NOT_FOUND",
                        "Ledger has no active Finance Dimension of type " + type + ".",
                        HttpStatus.NOT_FOUND, "segmentType"));

        List<String> segments = dimensionValueRepository
                .findByFinanceDimensionIdAndIsActiveTrue(dimension.getId())
                .stream()
                .sorted(Comparator.comparing(DimensionValue::getDisplayOrder).thenComparing(DimensionValue::getCode))
                .map(DimensionValue::getCode)
                .toList();

        List<PLSegmentPivotRow> pivotRows = accountBalanceRepository
                .findPLBySegmentPivot(legalEntityId, periodId, type.name());

        Map<String, AccountAgg> byAccountCode = new LinkedHashMap<>();
        for (PLSegmentPivotRow row : pivotRows) {
            String segmentValue = row.getSegmentValue();
            if (segmentValue == null || !segments.contains(segmentValue)) {
                continue;
            }
            AccountAgg agg = byAccountCode.computeIfAbsent(row.getNaturalAccountCode(),
                    code -> new AccountAgg(code, row.getNaturalAccountName(), row.getAccountQualifier()));

            BigDecimal net = "REVENUE".equals(row.getAccountQualifier())
                    ? row.getPtdCr().subtract(row.getPtdDr())
                    : row.getPtdDr().subtract(row.getPtdCr());
            agg.segmentNet().merge(segmentValue, net, BigDecimal::add);
        }

        List<PLBySegmentLine> revenueLines = new ArrayList<>();
        List<PLBySegmentLine> expenseLines = new ArrayList<>();
        for (AccountAgg agg : byAccountCode.values()) {
            Map<String, BigDecimal> segmentAmounts = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            boolean allZero = true;
            for (String segment : segments) {
                BigDecimal amount = agg.segmentNet().getOrDefault(segment, BigDecimal.ZERO);
                segmentAmounts.put(segment, amount);
                total = total.add(amount);
                if (amount.compareTo(BigDecimal.ZERO) != 0) {
                    allZero = false;
                }
            }

            if (!includeZeroBalances && allZero) {
                continue;
            }

            PLBySegmentLine line = PLBySegmentLine.builder()
                    .accountCode(agg.code())
                    .accountName(agg.name())
                    .accountQualifier(agg.qualifier())
                    .segmentAmounts(segmentAmounts)
                    .total(total)
                    .build();

            if ("REVENUE".equals(agg.qualifier())) {
                revenueLines.add(line);
            } else {
                expenseLines.add(line);
            }
        }

        Map<String, BigDecimal> totalRevenue = aggregateTotals(revenueLines, segments);
        Map<String, BigDecimal> totalExpenses = aggregateTotals(expenseLines, segments);
        Map<String, BigDecimal> netIncome = new LinkedHashMap<>();
        for (String segment : segments) {
            netIncome.put(segment, totalRevenue.get(segment).subtract(totalExpenses.get(segment)));
        }
        netIncome.put(TOTAL_KEY, totalRevenue.get(TOTAL_KEY).subtract(totalExpenses.get(TOTAL_KEY)));

        AccountingPeriod period = accountingPeriodRepository.findById(periodId)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND",
                        "Accounting period not found.", HttpStatus.NOT_FOUND));
        LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                .orElseThrow(() -> new EvyoogException("LEGAL_ENTITY_NOT_FOUND",
                        "Legal Entity not found.", HttpStatus.NOT_FOUND));

        return PLBySegmentResponse.builder()
                .legalEntityId(legalEntityId)
                .legalEntityName(legalEntity.getName())
                .accountingPeriodId(periodId)
                .periodName(period.getName())
                .fiscalYear(period.getFiscalYear())
                .segmentType(type.name())
                .segments(segments)
                .revenueLines(revenueLines)
                .expenseLines(expenseLines)
                .totalRevenue(totalRevenue)
                .totalExpenses(totalExpenses)
                .netIncome(netIncome)
                .generatedAt(LocalDate.now())
                .build();
    }

    private DimensionType parseSegmentType(String segmentType) {
        try {
            DimensionType type = DimensionType.valueOf(segmentType);
            if (type == DimensionType.NATURAL_ACCOUNT) {
                throw new IllegalArgumentException();
            }
            return type;
        } catch (IllegalArgumentException ex) {
            throw new EvyoogException("INVALID_SEGMENT_TYPE",
                    "Invalid segment type: " + segmentType + ". Must be a Finance Dimension type "
                            + "other than NATURAL_ACCOUNT.",
                    HttpStatus.BAD_REQUEST, "segmentType");
        }
    }

    private Map<String, BigDecimal> aggregateTotals(List<PLBySegmentLine> lines, List<String> segments) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (String segment : segments) {
            totals.put(segment, BigDecimal.ZERO);
        }
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (PLBySegmentLine line : lines) {
            for (String segment : segments) {
                totals.merge(segment, line.segmentAmounts().getOrDefault(segment, BigDecimal.ZERO), BigDecimal::add);
            }
            grandTotal = grandTotal.add(line.total());
        }
        totals.put(TOTAL_KEY, grandTotal);
        return totals;
    }

    private record AccountAgg(String code, String name, String qualifier, Map<String, BigDecimal> segmentNet) {
        AccountAgg(String code, String name, String qualifier) {
            this(code, name, qualifier, new LinkedHashMap<>());
        }
    }
}
