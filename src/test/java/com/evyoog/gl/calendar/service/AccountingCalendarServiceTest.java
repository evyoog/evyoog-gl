package com.evyoog.gl.calendar.service;

import com.evyoog.gl.calendar.domain.AccountingCalendar;
import com.evyoog.gl.calendar.dto.AccountingCalendarResponse;
import com.evyoog.gl.calendar.dto.CreateCalendarRequest;
import com.evyoog.gl.calendar.mapper.AccountingCalendarMapper;
import com.evyoog.gl.calendar.repository.AccountingCalendarRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.enterprise.domain.BusinessGroup;
import com.evyoog.gl.enterprise.domain.ConsumptionContext;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.domain.LegalEntityLedger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.service.AccountingPeriodService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingCalendarServiceTest {

    @Mock
    private AccountingCalendarRepository repository;
    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock
    private AccountingPeriodService accountingPeriodService;
    @Mock
    private AccountingCalendarMapper mapper;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private AccountingCalendarService service;

    private Ledger ledger() {
        Ledger ledger = Ledger.builder().code("LDG").name("Test Ledger").build();
        ledger.setId(UUID.randomUUID());
        return ledger;
    }

    private AccountingCalendarResponse responseFor(AccountingCalendar entity) {
        return new AccountingCalendarResponse(entity.getId(), entity.getLedger().getId(), entity.getLedger().getName(),
                entity.getName(), entity.getDescription(), entity.getFiscalYearStartMonth(), entity.getFiscalYearStartDay(),
                entity.getPeriodType(), entity.getPeriodsPerYear(), entity.isActive(), null, null, Instant.now(), Instant.now());
    }

    private List<AccountingPeriod> twelvePeriods() {
        return List.of(new AccountingPeriod(), new AccountingPeriod(), new AccountingPeriod(), new AccountingPeriod(),
                new AccountingPeriod(), new AccountingPeriod(), new AccountingPeriod(), new AccountingPeriod(),
                new AccountingPeriod(), new AccountingPeriod(), new AccountingPeriod(), new AccountingPeriod());
    }

    @Test
    void createCalendar_success_defaultAprilStart() {
        Ledger ledger = ledger();
        CreateCalendarRequest request = new CreateCalendarRequest(ledger.getId(), "FY Calendar", null,
                null, null, null, 2025);
        AccountingCalendar entity = new AccountingCalendar();
        AccountingCalendar saved = AccountingCalendar.builder().name("FY Calendar").ledger(ledger).build();
        saved.setId(UUID.randomUUID());

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(false);
        when(legalEntityLedgerRepository.findByLedgerId(ledger.getId())).thenReturn(List.of());
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(accountingPeriodService.generatePeriodsForFiscalYear(eq(saved.getId()), eq(2025), any()))
                .thenReturn(twelvePeriods());
        when(accountingPeriodService.deriveFiscalYearName(4, 2025)).thenReturn("2025-26");
        when(mapper.toResponse(saved)).thenReturn(responseFor(saved));

        AccountingCalendarResponse result = service.create(request, "prashanth");

        assertThat(result.currentFiscalYear()).isEqualTo("2025-26");
        assertThat(result.generatedPeriodCount()).isEqualTo(12);
    }

    @Test
    void createCalendar_success_generatesAdjustmentPeriodForFirstFiscalYear() {
        Ledger ledger = ledger();
        CreateCalendarRequest request = new CreateCalendarRequest(ledger.getId(), "FY Calendar", null,
                null, null, null, 2025);
        AccountingCalendar entity = new AccountingCalendar();
        AccountingCalendar saved = AccountingCalendar.builder().name("FY Calendar").ledger(ledger).build();
        saved.setId(UUID.randomUUID());

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(false);
        when(legalEntityLedgerRepository.findByLedgerId(ledger.getId())).thenReturn(List.of());
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(accountingPeriodService.generatePeriodsForFiscalYear(eq(saved.getId()), eq(2025), any()))
                .thenReturn(twelvePeriods());
        when(accountingPeriodService.deriveFiscalYearName(4, 2025)).thenReturn("2025-26");
        when(mapper.toResponse(saved)).thenReturn(responseFor(saved));

        service.create(request, "prashanth");

        org.mockito.Mockito.verify(accountingPeriodService)
                .generateAdjustmentPeriod(saved.getId(), "2025-26", "prashanth");
    }

    @Test
    void createCalendar_duplicateForSameLedger_throws409() {
        Ledger ledger = ledger();
        CreateCalendarRequest request = new CreateCalendarRequest(ledger.getId(), "FY Calendar", null,
                null, null, null, 2025);

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "CALENDAR_EXISTS");
    }

    @Test
    void createCalendar_customStartMonth_january() {
        Ledger ledger = ledger();
        CreateCalendarRequest request = new CreateCalendarRequest(ledger.getId(), "FY Calendar", null,
                1, null, null, 2025);
        AccountingCalendar entity = new AccountingCalendar();
        AccountingCalendar saved = AccountingCalendar.builder().name("FY Calendar").ledger(ledger).build();
        saved.setId(UUID.randomUUID());

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(false);
        when(legalEntityLedgerRepository.findByLedgerId(ledger.getId())).thenReturn(List.of());
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(accountingPeriodService.generatePeriodsForFiscalYear(eq(saved.getId()), eq(2025), any()))
                .thenReturn(twelvePeriods());
        when(accountingPeriodService.deriveFiscalYearName(1, 2025)).thenReturn("2025");
        when(mapper.toResponse(saved)).thenReturn(responseFor(saved));

        AccountingCalendarResponse result = service.create(request, "prashanth");

        assertThat(result.currentFiscalYear()).isEqualTo("2025");
    }

    @Test
    void deactivate_alwaysBlockedBecausePeriodsAlreadyGenerated() {
        UUID id = UUID.randomUUID();
        AccountingCalendar entity = new AccountingCalendar();
        entity.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.deactivate(id, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "CALENDAR_HAS_PERIODS");
    }

    @Test
    void createCalendar_usesProvisioningAnswersWhenRequestOmitsFiscalStart() {
        Ledger ledger = ledger();
        CreateCalendarRequest request = new CreateCalendarRequest(ledger.getId(), "FY Calendar", null,
                null, null, null, null);
        AccountingCalendar entity = new AccountingCalendar();
        AccountingCalendar saved = AccountingCalendar.builder().name("FY Calendar").ledger(ledger).build();
        saved.setId(UUID.randomUUID());

        ConsumptionContext context = ConsumptionContext.builder()
                .provisioningAnswers(Map.of("fiscalStartMonth", 4, "fiscalStartYear", 2024))
                .build();
        BusinessGroup businessGroup = BusinessGroup.builder().consumptionContext(context).build();
        LegalEntity legalEntity = LegalEntity.builder().businessGroup(businessGroup).build();
        LegalEntityLedger lel = LegalEntityLedger.builder().legalEntity(legalEntity).ledger(ledger).build();

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(false);
        when(legalEntityLedgerRepository.findByLedgerId(ledger.getId())).thenReturn(List.of(lel));
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(accountingPeriodService.generatePeriodsForFiscalYear(eq(saved.getId()), eq(2024), any()))
                .thenReturn(twelvePeriods());
        when(accountingPeriodService.deriveFiscalYearName(4, 2024)).thenReturn("2024-25");
        when(mapper.toResponse(saved)).thenReturn(responseFor(saved));

        AccountingCalendarResponse result = service.create(request, "prashanth");

        assertThat(result.currentFiscalYear()).isEqualTo("2024-25");
        assertThat(entity.getFiscalYearStartMonth()).isEqualTo(4);
    }
}
