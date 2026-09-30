package com.evyoog.gl.period.repository;

import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.domain.AccountingPeriodType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, UUID> {

    boolean existsByAccountingCalendarIdAndFiscalYear(UUID accountingCalendarId, String fiscalYear);

    long countByAccountingCalendarId(UUID accountingCalendarId);

    List<AccountingPeriod> findByAccountingCalendarIdOrderByStartDateAsc(UUID accountingCalendarId);

    List<AccountingPeriod> findByAccountingCalendarIdAndFiscalYearOrderByStartDateAsc(
            UUID accountingCalendarId, String fiscalYear);

    Optional<AccountingPeriod> findByAccountingCalendarIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID accountingCalendarId, LocalDate startDate, LocalDate endDate);

    Optional<AccountingPeriod> findTopByAccountingCalendarIdOrderByEndDateDesc(UUID accountingCalendarId);

    /** V33 Rule 3a/5a — the immediately preceding period number within the same fiscal year. */
    Optional<AccountingPeriod> findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(
            UUID accountingCalendarId, String fiscalYear, Integer periodNumber);

    /** V33 Rule 4 — the most recent REGULAR period strictly before a given date, i.e. the prior fiscal year's MAR period. */
    Optional<AccountingPeriod> findTopByAccountingCalendarIdAndPeriodTypeAndEndDateLessThanOrderByEndDateDesc(
            UUID accountingCalendarId, AccountingPeriodType periodType, LocalDate endDate);

    /** V33 Rule 4 — the ADJ period (if any) of a given fiscal year. */
    Optional<AccountingPeriod> findByAccountingCalendarIdAndFiscalYearAndPeriodType(
            UUID accountingCalendarId, String fiscalYear, AccountingPeriodType periodType);

    /** V33 Rule 5a — the last REGULAR period (e.g. MAR) of a given fiscal year. */
    Optional<AccountingPeriod> findTopByAccountingCalendarIdAndFiscalYearAndPeriodTypeOrderByPeriodNumberDesc(
            UUID accountingCalendarId, String fiscalYear, AccountingPeriodType periodType);
}
