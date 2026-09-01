package com.evyoog.gl.calendar.service;

import com.evyoog.gl.calendar.domain.AccountingCalendar;
import com.evyoog.gl.calendar.domain.PeriodType;
import com.evyoog.gl.calendar.dto.AccountingCalendarResponse;
import com.evyoog.gl.calendar.dto.CreateCalendarRequest;
import com.evyoog.gl.calendar.dto.UpdateCalendarRequest;
import com.evyoog.gl.calendar.mapper.AccountingCalendarMapper;
import com.evyoog.gl.calendar.repository.AccountingCalendarRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.domain.ConsumptionContext;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.service.AccountingPeriodService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountingCalendarService {

    private final AccountingCalendarRepository repository;
    private final LedgerRepository ledgerRepository;
    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final AccountingPeriodService accountingPeriodService;
    private final AccountingCalendarMapper mapper;
    private final AuditService auditService;

    @Transactional
    public AccountingCalendarResponse create(CreateCalendarRequest request, String performedBy) {
        Ledger ledger = ledgerRepository.findById(request.ledgerId())
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", request.ledgerId()));

        if (repository.existsByLedgerIdAndIsActiveTrue(ledger.getId())) {
            throw new EvyoogException("CALENDAR_EXISTS",
                    "This Ledger already has an Accounting Calendar. " +
                            "A Ledger may have exactly one Accounting Calendar.");
        }

        Map<String, Object> provisioningAnswers = resolveProvisioningAnswers(ledger.getId());

        int fiscalYearStartMonth = request.fiscalYearStartMonth() != null
                ? request.fiscalYearStartMonth() : intAnswer(provisioningAnswers, "fiscalStartMonth", 4);
        int fiscalYearStartDay = request.fiscalYearStartDay() != null ? request.fiscalYearStartDay() : 1;
        PeriodType periodType = request.periodType() != null ? request.periodType() : PeriodType.MONTHLY;
        int periodsPerYear = accountingPeriodService.periodsPerYear(periodType);
        int initialFiscalYear = request.initialFiscalYear() != null
                ? request.initialFiscalYear() : intAnswer(provisioningAnswers, "fiscalStartYear", LocalDate.now().getYear());

        AccountingCalendar entity = mapper.toEntity(request);
        entity.setLedger(ledger);
        entity.setFiscalYearStartMonth(fiscalYearStartMonth);
        entity.setFiscalYearStartDay(fiscalYearStartDay);
        entity.setPeriodType(periodType);
        entity.setPeriodsPerYear(periodsPerYear);
        entity.setCreatedBy(performedBy);
        entity.setUpdatedBy(performedBy);

        AccountingCalendar saved = repository.saveAndFlush(entity);

        // Delegates to AccountingPeriodService (GL-09), which persists the real
        // gl.accounting_period rows within this same transaction.
        List<AccountingPeriod> periods = accountingPeriodService.generatePeriodsForFiscalYear(
                saved.getId(), initialFiscalYear, performedBy);
        String fiscalYearName = accountingPeriodService.deriveFiscalYearName(fiscalYearStartMonth, initialFiscalYear);

        AccountingCalendarResponse response = mapper.toResponse(saved).withGeneratedInfo(periods.size(), fiscalYearName);

        auditService.log(AuditAction.CREATE, "accounting_calendar", saved.getId(), null, response, performedBy);

        return response;
    }

    @Transactional
    public AccountingCalendarResponse update(UUID id, UpdateCalendarRequest request, String performedBy) {
        AccountingCalendar entity = findOrThrow(id);
        AccountingCalendarResponse before = enrichCurrent(entity);

        mapper.updateFromRequest(request, entity);
        entity.setUpdatedBy(performedBy);

        AccountingCalendar saved = repository.saveAndFlush(entity);
        AccountingCalendarResponse response = enrichCurrent(saved);
        auditService.log(AuditAction.UPDATE, "accounting_calendar", saved.getId(), before, response, performedBy);

        return response;
    }

    @Transactional
    public void deactivate(UUID id, String performedBy) {
        findOrThrow(id);

        // GL-08 auto-generates periods synchronously on creation (Rule 2), so every existing
        // Accounting Calendar has already had its periods generated. Real period-existence
        // checking (e.g. "no journals posted yet") replaces this once GL-09 introduces
        // gl.accounting_period.
        throw new EvyoogException("CALENDAR_HAS_PERIODS",
                "Cannot delete an Accounting Calendar once its periods have been generated.", HttpStatus.CONFLICT);
    }

    @Transactional(readOnly = true)
    public AccountingCalendarResponse getById(UUID id) {
        return enrichCurrent(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Optional<AccountingCalendarResponse> getByLedgerId(UUID ledgerId) {
        return repository.findByLedgerId(ledgerId).map(this::enrichCurrent);
    }

    @Transactional(readOnly = true)
    public List<AccountingCalendarResponse> getAll() {
        return repository.findAll().stream()
                .map(this::enrichCurrent)
                .toList();
    }

    private AccountingCalendarResponse enrichCurrent(AccountingCalendar entity) {
        int currentFiscalYear = fiscalYearStartYearFor(LocalDate.now(), entity.getFiscalYearStartMonth(), entity.getFiscalYearStartDay());
        String fiscalYearName = accountingPeriodService.deriveFiscalYearName(entity.getFiscalYearStartMonth(), currentFiscalYear);
        long periodCount = accountingPeriodService.countPeriods(entity.getId());
        return mapper.toResponse(entity).withGeneratedInfo((int) periodCount, fiscalYearName);
    }

    private Map<String, Object> resolveProvisioningAnswers(UUID ledgerId) {
        return legalEntityLedgerRepository.findByLedgerId(ledgerId).stream()
                .map(lel -> lel.getLegalEntity().getBusinessGroup().getConsumptionContext())
                .filter(Objects::nonNull)
                .map(ConsumptionContext::getProvisioningAnswers)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Map.of());
    }

    private int intAnswer(Map<String, Object> answers, String key, int defaultValue) {
        Object value = answers.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private int fiscalYearStartYearFor(LocalDate date, int fiscalStartMonth, int fiscalStartDay) {
        LocalDate fiscalStartThisCalendarYear = LocalDate.of(date.getYear(), fiscalStartMonth, fiscalStartDay);
        return date.isBefore(fiscalStartThisCalendarYear) ? date.getYear() - 1 : date.getYear();
    }

    private AccountingCalendar findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AccountingCalendar", id));
    }
}
