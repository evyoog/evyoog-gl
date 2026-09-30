package com.evyoog.gl.period.service;

import com.evyoog.gl.calendar.domain.AccountingCalendar;
import com.evyoog.gl.calendar.domain.PeriodType;
import com.evyoog.gl.calendar.repository.AccountingCalendarRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.domain.AccountingPeriodType;
import com.evyoog.gl.period.dto.AccountingPeriodResponse;
import com.evyoog.gl.period.mapper.AccountingPeriodMapper;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical home of period-generation logic, promoted from GL-08's
 * AccountingCalendarService.generatePeriods(). AccountingCalendarService now
 * delegates to this service for both initial generation and fiscal-year
 * name formatting.
 */
@Service
@RequiredArgsConstructor
public class AccountingPeriodService {

    private final AccountingPeriodRepository repository;
    private final AccountingCalendarRepository accountingCalendarRepository;
    private final AccountingPeriodMapper mapper;
    private final AuditService auditService;

    @Transactional
    public List<AccountingPeriod> generatePeriodsForFiscalYear(UUID calendarId, int fiscalYear, String performedBy) {
        AccountingCalendar calendar = accountingCalendarRepository.findById(calendarId)
                .orElseThrow(() -> new EvyoogException("CALENDAR_NOT_FOUND",
                        "Accounting Calendar not found.", HttpStatus.NOT_FOUND));

        String fiscalYearName = deriveFiscalYearName(calendar.getFiscalYearStartMonth(), fiscalYear);

        if (repository.existsByAccountingCalendarIdAndFiscalYear(calendarId, fiscalYearName)) {
            throw new EvyoogException("PERIODS_ALREADY_EXIST",
                    "Periods already exist for fiscal year " + fiscalYearName + ". Use the existing periods.");
        }

        List<GeneratedPeriod> definitions = generatePeriodDefinitions(calendar.getFiscalYearStartMonth(),
                calendar.getFiscalYearStartDay(), calendar.getPeriodType(), fiscalYear);

        List<AccountingPeriod> periods = new ArrayList<>();
        for (GeneratedPeriod definition : definitions) {
            AccountingPeriod period = AccountingPeriod.builder()
                    .accountingCalendar(calendar)
                    .name(definition.name())
                    .periodNumber(definition.periodNumber())
                    .fiscalYear(definition.fiscalYear())
                    .periodType(AccountingPeriodType.REGULAR)
                    .quarterNumber(definition.quarter())
                    .startDate(definition.startDate())
                    .endDate(definition.endDate())
                    .createdBy(performedBy)
                    .updatedBy(performedBy)
                    .build();

            AccountingPeriod saved = repository.saveAndFlush(period);
            auditService.log(AuditAction.CREATE, "accounting_period", saved.getId(), null,
                    mapper.toResponse(saved), performedBy);
            periods.add(saved);
        }

        return periods;
    }

    @Transactional
    public List<AccountingPeriod> generateNextFiscalYear(UUID calendarId, String performedBy) {
        AccountingPeriod latest = repository.findTopByAccountingCalendarIdOrderByEndDateDesc(calendarId)
                .orElseThrow(() -> new EvyoogException("NO_EXISTING_PERIODS",
                        "No periods exist yet for this Calendar. Generate the initial fiscal year first.",
                        HttpStatus.CONFLICT));

        int nextFiscalYear = latest.getEndDate().plusDays(1).getYear();
        return generatePeriodsForFiscalYear(calendarId, nextFiscalYear, performedBy);
    }

    /**
     * V33/V34 Period Management Controls — generates the Adjustment Period
     * (period 13, a 2-week window right after the fiscal year's last REGULAR
     * period) for a given fiscal year. Called once from
     * {@code AccountingCalendarService.create()} right after the calendar's
     * FIRST fiscal year is generated, so every NEW calendar gets its
     * Adjustment Period automatically going forward — V33/V34's SQL-level
     * backfill only ever had to cover calendars that pre-date this method.
     *
     * <p>Deliberately NOT folded into {@link #generatePeriodsForFiscalYear}
     * itself: that method is shared with {@link #generateNextFiscalYear},
     * which pre-generates a FUTURE fiscal year's periods ahead of time (e.g.
     * a calendar that already has both FY2026-27 and FY2027-28) — an
     * Adjustment Period only ever belongs to the fiscal year currently being
     * closed, not to one pre-generated in advance.
     *
     * <p>Idempotent and self-guarding: returns empty if this fiscal year
     * already has an ADJUSTMENT period, if no REGULAR periods exist for it
     * yet, or if period number 13 is already taken by a REGULAR period
     * (e.g. a FISCAL_4_4_5 calendar, which already runs 13 regular periods
     * and has no room left for one).
     */
    @Transactional
    public Optional<AccountingPeriod> generateAdjustmentPeriod(UUID calendarId, String fiscalYear, String performedBy) {
        AccountingCalendar calendar = accountingCalendarRepository.findById(calendarId)
                .orElseThrow(() -> new EvyoogException("CALENDAR_NOT_FOUND",
                        "Accounting Calendar not found.", HttpStatus.NOT_FOUND));

        if (repository.findByAccountingCalendarIdAndFiscalYearAndPeriodType(
                calendarId, fiscalYear, AccountingPeriodType.ADJUSTMENT).isPresent()) {
            return Optional.empty();
        }
        if (repository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendarId, fiscalYear, 13).isPresent()) {
            return Optional.empty(); // period 13 already taken by a REGULAR period (e.g. FISCAL_4_4_5)
        }

        List<AccountingPeriod> regularPeriods = repository
                .findByAccountingCalendarIdAndFiscalYearOrderByStartDateAsc(calendarId, fiscalYear).stream()
                .filter(p -> p.getPeriodType() == AccountingPeriodType.REGULAR)
                .toList();
        if (regularPeriods.isEmpty()) {
            return Optional.empty();
        }

        AccountingPeriod lastRegular = regularPeriods.get(regularPeriods.size() - 1);
        LocalDate start = lastRegular.getEndDate().plusDays(1);
        LocalDate end = start.plusDays(14);

        AccountingPeriod adjustment = AccountingPeriod.builder()
                .accountingCalendar(calendar)
                .name("ADJ-" + start.getYear())
                .periodNumber(13)
                .fiscalYear(fiscalYear)
                .periodType(AccountingPeriodType.ADJUSTMENT)
                .quarterNumber(4)
                .startDate(start)
                .endDate(end)
                .createdBy(performedBy)
                .updatedBy(performedBy)
                .build();

        AccountingPeriod saved = repository.saveAndFlush(adjustment);
        auditService.log(AuditAction.CREATE, "accounting_period", saved.getId(), null,
                mapper.toResponse(saved), performedBy);
        return Optional.of(saved);
    }

    @Transactional(readOnly = true)
    public AccountingPeriod findPeriodByDate(UUID calendarId, LocalDate accountingDate) {
        return repository
                .findByAccountingCalendarIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        calendarId, accountingDate, accountingDate)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_FOUND",
                        "No accounting period found for date " + accountingDate +
                                ". Ensure periods have been generated for this fiscal year.", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<AccountingPeriodResponse> listPeriods(UUID calendarId, String fiscalYear) {
        List<AccountingPeriod> periods = fiscalYear != null
                ? repository.findByAccountingCalendarIdAndFiscalYearOrderByStartDateAsc(calendarId, fiscalYear)
                : repository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId);
        return periods.stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AccountingPeriodResponse getById(UUID id) {
        return mapper.toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public AccountingPeriodResponse getByDate(UUID calendarId, LocalDate date) {
        return mapper.toResponse(findPeriodByDate(calendarId, date));
    }

    @Transactional(readOnly = true)
    public long countPeriods(UUID calendarId) {
        return repository.countByAccountingCalendarId(calendarId);
    }

    public int periodsPerYear(PeriodType periodType) {
        return switch (periodType) {
            case MONTHLY -> 12;
            case QUARTERLY -> 4;
            case FISCAL_4_4_5 -> 13;
        };
    }

    public String deriveFiscalYearName(int fiscalYearStartMonth, int fiscalYearStartYear) {
        if (fiscalYearStartMonth == 1) {
            return String.valueOf(fiscalYearStartYear);
        }
        return fiscalYearStartYear + "-" + String.valueOf(fiscalYearStartYear + 1).substring(2);
    }

    private String derivePeriodName(int month, int year) {
        return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH) + "-" + year;
    }

    private int deriveQuarter(int month, int fiscalYearStartMonth) {
        int fiscalMonth = ((month - fiscalYearStartMonth + 12) % 12) + 1;
        return (fiscalMonth - 1) / 3 + 1;
    }

    private List<GeneratedPeriod> generatePeriodDefinitions(int fiscalYearStartMonth, int fiscalYearStartDay,
                                                              PeriodType periodType, int fiscalYear) {
        return switch (periodType) {
            case MONTHLY -> generateMonthlyPeriods(fiscalYearStartMonth, fiscalYear);
            case QUARTERLY -> generateQuarterlyPeriods(fiscalYearStartMonth, fiscalYear);
            case FISCAL_4_4_5 -> generateFiscal445Periods(fiscalYearStartMonth, fiscalYearStartDay, fiscalYear);
        };
    }

    private List<GeneratedPeriod> generateMonthlyPeriods(int fiscalYearStartMonth, int fiscalYear) {
        String fiscalYearName = deriveFiscalYearName(fiscalYearStartMonth, fiscalYear);
        List<GeneratedPeriod> periods = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            int month = ((fiscalYearStartMonth - 1 + i) % 12) + 1;
            int year = fiscalYear + ((fiscalYearStartMonth - 1 + i) / 12);
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = YearMonth.of(year, month).atEndOfMonth();

            periods.add(new GeneratedPeriod(derivePeriodName(month, year), start, end, i + 1,
                    deriveQuarter(month, fiscalYearStartMonth), fiscalYearName));
        }

        return periods;
    }

    private List<GeneratedPeriod> generateQuarterlyPeriods(int fiscalYearStartMonth, int fiscalYear) {
        String fiscalYearName = deriveFiscalYearName(fiscalYearStartMonth, fiscalYear);
        List<GeneratedPeriod> periods = new ArrayList<>();

        for (int quarter = 0; quarter < 4; quarter++) {
            int startOffset = quarter * 3;
            int startMonth = ((fiscalYearStartMonth - 1 + startOffset) % 12) + 1;
            int startYear = fiscalYear + ((fiscalYearStartMonth - 1 + startOffset) / 12);
            LocalDate start = LocalDate.of(startYear, startMonth, 1);

            int endOffset = startOffset + 2;
            int endMonth = ((fiscalYearStartMonth - 1 + endOffset) % 12) + 1;
            int endYear = fiscalYear + ((fiscalYearStartMonth - 1 + endOffset) / 12);
            LocalDate end = YearMonth.of(endYear, endMonth).atEndOfMonth();

            periods.add(new GeneratedPeriod("Q" + (quarter + 1) + "-" + fiscalYearName, start, end,
                    quarter + 1, quarter + 1, fiscalYearName));
        }

        return periods;
    }

    /**
     * Phase 1 approximation: 13 periods of 4 weeks each, with the final period absorbing
     * whatever remains so the fiscal year closes exactly on the day before the next one starts.
     * A true retail 4-4-5 week-anchored calendar is out of scope until a subledger needs it.
     */
    private List<GeneratedPeriod> generateFiscal445Periods(int fiscalYearStartMonth, int fiscalYearStartDay, int fiscalYear) {
        String fiscalYearName = deriveFiscalYearName(fiscalYearStartMonth, fiscalYear);
        LocalDate fiscalStart = LocalDate.of(fiscalYear, fiscalYearStartMonth, fiscalYearStartDay);
        LocalDate fiscalEnd = fiscalStart.plusYears(1).minusDays(1);

        List<GeneratedPeriod> periods = new ArrayList<>();
        LocalDate cursor = fiscalStart;

        for (int i = 1; i <= 13; i++) {
            LocalDate start = cursor;
            LocalDate end = (i == 13) ? fiscalEnd : start.plusWeeks(4).minusDays(1);
            int quarter = Math.min(((i - 1) / 4) + 1, 4);

            periods.add(new GeneratedPeriod("P" + i + "-" + fiscalYearName, start, end, i, quarter, fiscalYearName));
            cursor = end.plusDays(1);
        }

        return periods;
    }

    private AccountingPeriod findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AccountingPeriod", id));
    }

    private record GeneratedPeriod(String name, LocalDate startDate, LocalDate endDate,
                                    int periodNumber, int quarter, String fiscalYear) {
    }
}
