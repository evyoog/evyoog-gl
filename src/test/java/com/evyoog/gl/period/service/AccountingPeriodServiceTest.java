package com.evyoog.gl.period.service;

import com.evyoog.gl.calendar.domain.AccountingCalendar;
import com.evyoog.gl.calendar.domain.PeriodType;
import com.evyoog.gl.calendar.repository.AccountingCalendarRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.dto.AccountingPeriodResponse;
import com.evyoog.gl.period.mapper.AccountingPeriodMapper;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingPeriodServiceTest {

    @Mock
    private AccountingPeriodRepository repository;
    @Mock
    private AccountingCalendarRepository accountingCalendarRepository;
    @Mock
    private AccountingPeriodMapper mapper;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private AccountingPeriodService service;

    private AccountingCalendar calendar(int startMonth, int startDay, PeriodType periodType) {
        AccountingCalendar calendar = AccountingCalendar.builder()
                .name("FY Calendar")
                .fiscalYearStartMonth(startMonth)
                .fiscalYearStartDay(startDay)
                .periodType(periodType)
                .build();
        calendar.setId(UUID.randomUUID());
        return calendar;
    }

    private void stubSaveAndAudit() {
        when(repository.saveAndFlush(any(AccountingPeriod.class))).thenAnswer(inv -> {
            AccountingPeriod period = inv.getArgument(0);
            period.setId(UUID.randomUUID());
            return period;
        });
        when(mapper.toResponse(any(AccountingPeriod.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));
    }

    private AccountingPeriodResponse responseFor(AccountingPeriod entity) {
        return new AccountingPeriodResponse(entity.getId(), UUID.randomUUID(), "FY Calendar", entity.getName(),
                entity.getPeriodNumber(), entity.getFiscalYear(), entity.getPeriodType(), entity.getQuarterNumber(),
                entity.getStartDate(), entity.getEndDate(), entity.getIsActive(), Instant.now());
    }

    @Test
    void generatePeriodsForFiscalYear_monthly_generates12Periods() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2025-26")).thenReturn(false);
        stubSaveAndAudit();

        List<AccountingPeriod> periods = service.generatePeriodsForFiscalYear(calendar.getId(), 2025, "prashanth");

        assertThat(periods).hasSize(12);
    }

    @Test
    void generatePeriodsForFiscalYear_duplicateFiscalYear_throws409() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2025-26")).thenReturn(true);

        assertThatThrownBy(() -> service.generatePeriodsForFiscalYear(calendar.getId(), 2025, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "PERIODS_ALREADY_EXIST");
    }

    @Test
    void generatePeriodsForFiscalYear_correctPeriodNames() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2025-26")).thenReturn(false);
        stubSaveAndAudit();

        List<AccountingPeriod> periods = service.generatePeriodsForFiscalYear(calendar.getId(), 2025, "prashanth");

        assertThat(periods.get(0).getName()).isEqualTo("APR-2025");
        assertThat(periods.get(1).getName()).isEqualTo("MAY-2025");
        assertThat(periods.get(8).getName()).isEqualTo("DEC-2025");
        assertThat(periods.get(9).getName()).isEqualTo("JAN-2026");
        assertThat(periods.get(11).getName()).isEqualTo("MAR-2026");
        assertThat(periods).allSatisfy(p -> assertThat(p.getFiscalYear()).isEqualTo("2025-26"));
    }

    @Test
    void generatePeriodsForFiscalYear_correctQuarterAssignment() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2025-26")).thenReturn(false);
        stubSaveAndAudit();

        List<AccountingPeriod> periods = service.generatePeriodsForFiscalYear(calendar.getId(), 2025, "prashanth");

        assertThat(periods.get(0).getQuarterNumber()).isEqualTo(1);  // APR
        assertThat(periods.get(2).getQuarterNumber()).isEqualTo(1);  // JUN
        assertThat(periods.get(3).getQuarterNumber()).isEqualTo(2);  // JUL
        assertThat(periods.get(5).getQuarterNumber()).isEqualTo(2);  // SEP
        assertThat(periods.get(6).getQuarterNumber()).isEqualTo(3);  // OCT
        assertThat(periods.get(8).getQuarterNumber()).isEqualTo(3);  // DEC
        assertThat(periods.get(9).getQuarterNumber()).isEqualTo(4);  // JAN
        assertThat(periods.get(11).getQuarterNumber()).isEqualTo(4); // MAR
    }

    @Test
    void generatePeriodsForFiscalYear_correctDateRanges() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2025-26")).thenReturn(false);
        stubSaveAndAudit();

        List<AccountingPeriod> periods = service.generatePeriodsForFiscalYear(calendar.getId(), 2025, "prashanth");

        AccountingPeriod first = periods.get(0);
        assertThat(first.getStartDate()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(first.getEndDate()).isEqualTo(LocalDate.of(2025, 4, 30));

        AccountingPeriod last = periods.get(11);
        assertThat(last.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(last.getEndDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void generatePeriodsForFiscalYear_february_correctEndDate() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2025-26")).thenReturn(false);
        stubSaveAndAudit();

        // Fiscal year 2025 (Apr 2025 - Mar 2026) -> February 2026 is not a leap year: 28 days
        List<AccountingPeriod> periods = service.generatePeriodsForFiscalYear(calendar.getId(), 2025, "prashanth");
        AccountingPeriod feb2026 = periods.get(10);
        assertThat(feb2026.getName()).isEqualTo("FEB-2026");
        assertThat(feb2026.getEndDate()).isEqualTo(LocalDate.of(2026, 2, 28));

        // Fiscal year 2027 (Apr 2027 - Mar 2028) -> February 2028 is a leap year: 29 days
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2027-28")).thenReturn(false);
        List<AccountingPeriod> leapYearPeriods = service.generatePeriodsForFiscalYear(calendar.getId(), 2027, "prashanth");
        AccountingPeriod feb2028 = leapYearPeriods.get(10);
        assertThat(feb2028.getName()).isEqualTo("FEB-2028");
        assertThat(feb2028.getEndDate()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void findPeriodByDate_found() {
        UUID calendarId = UUID.randomUUID();
        AccountingPeriod period = AccountingPeriod.builder()
                .name("APR-2025").startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 4, 30)).build();
        when(repository.findByAccountingCalendarIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                calendarId, LocalDate.of(2025, 4, 15), LocalDate.of(2025, 4, 15)))
                .thenReturn(Optional.of(period));

        AccountingPeriod result = service.findPeriodByDate(calendarId, LocalDate.of(2025, 4, 15));

        assertThat(result.getName()).isEqualTo("APR-2025");
    }

    @Test
    void findPeriodByDate_notFound_throws() {
        UUID calendarId = UUID.randomUUID();
        when(repository.findByAccountingCalendarIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findPeriodByDate(calendarId, LocalDate.of(2025, 4, 15)))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "PERIOD_NOT_FOUND");
    }

    @Test
    void generateNextFiscalYear_generatesCorrectYear() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        AccountingPeriod latest = AccountingPeriod.builder()
                .name("MAR-2026").endDate(LocalDate.of(2026, 3, 31)).build();

        when(repository.findTopByAccountingCalendarIdOrderByEndDateDesc(calendar.getId()))
                .thenReturn(Optional.of(latest));
        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.existsByAccountingCalendarIdAndFiscalYear(calendar.getId(), "2026-27")).thenReturn(false);
        stubSaveAndAudit();

        List<AccountingPeriod> periods = service.generateNextFiscalYear(calendar.getId(), "prashanth");

        assertThat(periods).hasSize(12);
        assertThat(periods.get(0).getName()).isEqualTo("APR-2026");
        assertThat(periods.get(0).getFiscalYear()).isEqualTo("2026-27");
    }

    // ---- generateAdjustmentPeriod() — V33/V34 ----

    private AccountingPeriod regularPeriod(int periodNumber, LocalDate start, LocalDate end) {
        AccountingPeriod p = AccountingPeriod.builder()
                .name(start.getMonth() + "-" + start.getYear())
                .periodNumber(periodNumber)
                .fiscalYear("2025-26")
                .periodType(com.evyoog.gl.period.domain.AccountingPeriodType.REGULAR)
                .quarterNumber((periodNumber - 1) / 3 + 1)
                .startDate(start)
                .endDate(end)
                .build();
        p.setId(UUID.randomUUID());
        return p;
    }

    @Test
    void generateAdjustmentPeriod_success_createsAdjPeriodRightAfterLastRegular() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        AccountingPeriod marPeriod = regularPeriod(12, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.findByAccountingCalendarIdAndFiscalYearAndPeriodType(
                calendar.getId(), "2025-26", com.evyoog.gl.period.domain.AccountingPeriodType.ADJUSTMENT))
                .thenReturn(Optional.empty());
        when(repository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendar.getId(), "2025-26", 13))
                .thenReturn(Optional.empty());
        when(repository.findByAccountingCalendarIdAndFiscalYearOrderByStartDateAsc(calendar.getId(), "2025-26"))
                .thenReturn(List.of(marPeriod));
        stubSaveAndAudit();

        Optional<AccountingPeriod> result = service.generateAdjustmentPeriod(calendar.getId(), "2025-26", "prashanth");

        assertThat(result).isPresent();
        AccountingPeriod adj = result.get();
        assertThat(adj.getName()).isEqualTo("ADJ-2026");
        assertThat(adj.getPeriodNumber()).isEqualTo(13);
        assertThat(adj.getFiscalYear()).isEqualTo("2025-26");
        assertThat(adj.getPeriodType()).isEqualTo(com.evyoog.gl.period.domain.AccountingPeriodType.ADJUSTMENT);
        assertThat(adj.getStartDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(adj.getEndDate()).isEqualTo(LocalDate.of(2026, 4, 15));
    }

    @Test
    void generateAdjustmentPeriod_alreadyExists_returnsEmptyAndDoesNotSave() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);
        AccountingPeriod existingAdj = regularPeriod(13, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15));

        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.findByAccountingCalendarIdAndFiscalYearAndPeriodType(
                calendar.getId(), "2025-26", com.evyoog.gl.period.domain.AccountingPeriodType.ADJUSTMENT))
                .thenReturn(Optional.of(existingAdj));

        Optional<AccountingPeriod> result = service.generateAdjustmentPeriod(calendar.getId(), "2025-26", "prashanth");

        assertThat(result).isEmpty();
    }

    @Test
    void generateAdjustmentPeriod_periodNumber13AlreadyTakenByRegular_returnsEmpty() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.FISCAL_4_4_5);
        AccountingPeriod p13 = regularPeriod(13, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 28));

        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.findByAccountingCalendarIdAndFiscalYearAndPeriodType(
                calendar.getId(), "2025-26", com.evyoog.gl.period.domain.AccountingPeriodType.ADJUSTMENT))
                .thenReturn(Optional.empty());
        when(repository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendar.getId(), "2025-26", 13))
                .thenReturn(Optional.of(p13));

        Optional<AccountingPeriod> result = service.generateAdjustmentPeriod(calendar.getId(), "2025-26", "prashanth");

        assertThat(result).isEmpty();
    }

    @Test
    void generateAdjustmentPeriod_noRegularPeriodsYet_returnsEmpty() {
        AccountingCalendar calendar = calendar(4, 1, PeriodType.MONTHLY);

        when(accountingCalendarRepository.findById(calendar.getId())).thenReturn(Optional.of(calendar));
        when(repository.findByAccountingCalendarIdAndFiscalYearAndPeriodType(
                calendar.getId(), "2025-26", com.evyoog.gl.period.domain.AccountingPeriodType.ADJUSTMENT))
                .thenReturn(Optional.empty());
        when(repository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendar.getId(), "2025-26", 13))
                .thenReturn(Optional.empty());
        when(repository.findByAccountingCalendarIdAndFiscalYearOrderByStartDateAsc(calendar.getId(), "2025-26"))
                .thenReturn(List.of());

        Optional<AccountingPeriod> result = service.generateAdjustmentPeriod(calendar.getId(), "2025-26", "prashanth");

        assertThat(result).isEmpty();
    }

    @Test
    void generateAdjustmentPeriod_calendarNotFound_throws() {
        UUID calendarId = UUID.randomUUID();
        when(accountingCalendarRepository.findById(calendarId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateAdjustmentPeriod(calendarId, "2025-26", "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "CALENDAR_NOT_FOUND");
    }
}
