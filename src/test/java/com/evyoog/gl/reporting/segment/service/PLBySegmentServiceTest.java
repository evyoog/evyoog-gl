package com.evyoog.gl.reporting.segment.service;

import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.posting.repository.PLSegmentPivotRow;
import com.evyoog.gl.reporting.segment.dto.PLBySegmentResponse;
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
class PLBySegmentServiceTest {

    @Mock private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock private AccountBalanceRepository accountBalanceRepository;
    @Mock private FinanceDimensionRepository financeDimensionRepository;
    @Mock private DimensionValueRepository dimensionValueRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private LegalEntityRepository legalEntityRepository;

    private PLBySegmentService service;

    private UUID legalEntityId;
    private UUID periodId;
    private UUID ledgerId;
    private UUID dimensionId;
    private LegalEntity legalEntity;
    private AccountingPeriod period;

    @BeforeEach
    void setUp() {
        service = new PLBySegmentService(legalEntityLedgerRepository, accountBalanceRepository,
                financeDimensionRepository, dimensionValueRepository, accountingPeriodRepository,
                legalEntityRepository);

        legalEntityId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        ledgerId = UUID.randomUUID();
        dimensionId = UUID.randomUUID();
        legalEntity = LegalEntity.builder().id(legalEntityId).code("LE1").name("Orbinox Valves").build();
        period = AccountingPeriod.builder().id(periodId).name("APR-2025").fiscalYear("2025-26").build();
    }

    private Ledger ledger(FinanceMode mode) {
        return Ledger.builder().id(ledgerId).code("LDG").name("Primary Ledger").financeMode(mode).build();
    }

    private DimensionValue segmentValue(String code, int order) {
        return DimensionValue.builder().id(UUID.randomUUID()).code(code).name(code).displayOrder(order).build();
    }

    private PLSegmentPivotRow row(String code, String name, String qualifier, String segment,
                                   String ptdDr, String ptdCr) {
        return new PLSegmentPivotRow() {
            public String getNaturalAccountCode() { return code; }
            public String getNaturalAccountName() { return name; }
            public String getAccountQualifier() { return qualifier; }
            public String getSegmentValue() { return segment; }
            public BigDecimal getPtdDr() { return new BigDecimal(ptdDr); }
            public BigDecimal getPtdCr() { return new BigDecimal(ptdCr); }
        };
    }

    private void stubHappyPath(List<PLSegmentPivotRow> rows, List<DimensionValue> segmentValues,
                                DimensionType type) {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        when(accountBalanceRepository.existsByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(true);
        FinanceDimension dimension = FinanceDimension.builder().id(dimensionId).dimensionType(type).build();
        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, type))
                .thenReturn(Optional.of(dimension));
        when(dimensionValueRepository.findByFinanceDimensionIdAndIsActiveTrue(dimensionId))
                .thenReturn(segmentValues);
        when(accountBalanceRepository.findPLBySegmentPivot(legalEntityId, periodId, type.name()))
                .thenReturn(rows);
        lenient().when(accountingPeriodRepository.findById(periodId)).thenReturn(Optional.of(period));
        lenient().when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
    }

    @Test
    void testPLBySegment_costCentreType_returnsCorrectPivot() {
        stubHappyPath(
                List.of(
                        row("4100", "Sales", "REVENUE", "CC-SAL", "0", "7500000.00"),
                        row("5100", "Raw Material", "EXPENSE", "CC-MFG", "2200000.00", "0")),
                List.of(segmentValue("CC-MFG", 0), segmentValue("CC-SAL", 1)),
                DimensionType.COST_CENTRE);

        PLBySegmentResponse response = service.generate(legalEntityId, periodId, "COST_CENTRE", false);

        assertThat(response.segmentType()).isEqualTo("COST_CENTRE");
        assertThat(response.segments()).containsExactly("CC-MFG", "CC-SAL");
        assertThat(response.revenueLines()).hasSize(1);
        assertThat(response.revenueLines().get(0).segmentAmounts().get("CC-SAL"))
                .isEqualByComparingTo("7500000.00");
        assertThat(response.expenseLines()).hasSize(1);
        assertThat(response.expenseLines().get(0).segmentAmounts().get("CC-MFG"))
                .isEqualByComparingTo("2200000.00");
    }

    @Test
    void testPLBySegment_productType_returnsCorrectPivot() {
        stubHappyPath(
                List.of(row("4100", "Sales", "REVENUE", "GATE-VLV", "0", "1000.00")),
                List.of(segmentValue("GATE-VLV", 0), segmentValue("BALL-VLV", 1)),
                DimensionType.PRODUCT);

        PLBySegmentResponse response = service.generate(legalEntityId, periodId, "PRODUCT", false);

        assertThat(response.segmentType()).isEqualTo("PRODUCT");
        assertThat(response.revenueLines().get(0).segmentAmounts().get("GATE-VLV"))
                .isEqualByComparingTo("1000.00");
        assertThat(response.revenueLines().get(0).segmentAmounts().get("BALL-VLV"))
                .isEqualByComparingTo("0");
    }

    @Test
    void testPLBySegment_verifyNetIncomeCalculation() {
        stubHappyPath(
                List.of(
                        row("4100", "Sales", "REVENUE", "CC-SAL", "0", "1000.00"),
                        row("5100", "Raw Material", "EXPENSE", "CC-SAL", "400.00", "0")),
                List.of(segmentValue("CC-SAL", 0)),
                DimensionType.COST_CENTRE);

        PLBySegmentResponse response = service.generate(legalEntityId, periodId, "COST_CENTRE", false);

        assertThat(response.netIncome().get("CC-SAL")).isEqualByComparingTo("600.00");
        assertThat(response.netIncome().get("total")).isEqualByComparingTo("600.00");
        assertThat(response.totalRevenue().get("total")).isEqualByComparingTo("1000.00");
        assertThat(response.totalExpenses().get("total")).isEqualByComparingTo("400.00");
    }

    @Test
    void testPLBySegment_revenueNormalBalance_creditMinusDr() {
        stubHappyPath(
                List.of(row("4100", "Sales", "REVENUE", "CC-SAL", "50.00", "300.00")),
                List.of(segmentValue("CC-SAL", 0)),
                DimensionType.COST_CENTRE);

        PLBySegmentResponse response = service.generate(legalEntityId, periodId, "COST_CENTRE", false);

        assertThat(response.revenueLines().get(0).total()).isEqualByComparingTo("250.00");
    }

    @Test
    void testPLBySegment_expenseNormalBalance_drMinusCredit() {
        stubHappyPath(
                List.of(row("5100", "Raw Material", "EXPENSE", "CC-MFG", "300.00", "50.00")),
                List.of(segmentValue("CC-MFG", 0)),
                DimensionType.COST_CENTRE);

        PLBySegmentResponse response = service.generate(legalEntityId, periodId, "COST_CENTRE", false);

        assertThat(response.expenseLines().get(0).total()).isEqualByComparingTo("250.00");
    }

    @Test
    void testPLBySegment_excludesZeroBalanceLines_whenIncludeZeroBalancesFalse() {
        stubHappyPath(
                List.of(row("4100", "Sales", "REVENUE", "CC-SAL", "100.00", "100.00")),
                List.of(segmentValue("CC-SAL", 0)),
                DimensionType.COST_CENTRE);

        PLBySegmentResponse response = service.generate(legalEntityId, periodId, "COST_CENTRE", false);

        assertThat(response.revenueLines()).isEmpty();
    }

    @Test
    void testPLBySegment_includesZeroBalanceLines_whenIncludeZeroBalancesTrue() {
        stubHappyPath(
                List.of(row("4100", "Sales", "REVENUE", "CC-SAL", "100.00", "100.00")),
                List.of(segmentValue("CC-SAL", 0)),
                DimensionType.COST_CENTRE);

        PLBySegmentResponse response = service.generate(legalEntityId, periodId, "COST_CENTRE", true);

        assertThat(response.revenueLines()).hasSize(1);
    }

    @Test
    void testPLBySegment_invalidSegmentType_throws400() {
        assertThatThrownBy(() -> service.generate(legalEntityId, periodId, "NOT_A_TYPE", false))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_SEGMENT_TYPE");
    }

    @Test
    void testPLBySegment_naturalAccountSegmentType_throws400() {
        assertThatThrownBy(() -> service.generate(legalEntityId, periodId, "NATURAL_ACCOUNT", false))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_SEGMENT_TYPE");
    }

    @Test
    void testPLBySegment_eventOnlyMode_throws422() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.EVENT_ONLY)));

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId, "COST_CENTRE", false))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "EVENT_ONLY_NOT_SUPPORTED");
    }

    @Test
    void testPLBySegment_noBalances_throws404() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        when(accountBalanceRepository.existsByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId, "COST_CENTRE", false))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NO_BALANCES_FOUND");
    }

    @Test
    void testPLBySegment_noSegmentDimension_throws404() {
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger(FinanceMode.THICK)));
        when(accountBalanceRepository.existsByLegalEntityIdAndAccountingPeriodId(legalEntityId, periodId))
                .thenReturn(true);
        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(
                        ledgerId, DimensionType.PROJECT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(legalEntityId, periodId, "PROJECT", false))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "SEGMENT_DIMENSION_NOT_FOUND");
    }
}
