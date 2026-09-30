package com.evyoog.gl.periodmanagement.service;

import com.evyoog.gl.auth.domain.Role;
import com.evyoog.gl.auth.domain.UserRole;
import com.evyoog.gl.auth.repository.UserRoleRepository;
import com.evyoog.gl.calendar.domain.AccountingCalendar;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.enterprise.domain.AccountingStandard;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.domain.AccountingPeriodType;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.periodmanagement.domain.LegalEntityPeriodConfig;
import com.evyoog.gl.periodstatus.domain.PeriodStatus;
import com.evyoog.gl.periodstatus.domain.PeriodStatusEnum;
import com.evyoog.gl.periodstatus.dto.PeriodStatusResponse;
import com.evyoog.gl.periodstatus.repository.PeriodStatusRepository;
import com.evyoog.gl.periodstatus.service.PeriodStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeriodManagementServiceTest {

    @Mock
    private PeriodStatusRepository periodStatusRepository;
    @Mock
    private AccountingPeriodRepository accountingPeriodRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private LegalEntityPeriodConfigService configService;
    @Mock
    private PeriodStatusService periodStatusService;
    @Mock
    private AuditService auditService;

    private PeriodManagementService service;

    private UUID legalEntityId;
    private UUID calendarId;
    private UUID actingUserId;
    private LegalEntity legalEntity;
    private AccountingCalendar calendar;

    @BeforeEach
    void setUp() {
        service = new PeriodManagementService(periodStatusRepository, accountingPeriodRepository,
                userRoleRepository, configService, periodStatusService, auditService);

        legalEntityId = UUID.randomUUID();
        calendarId = UUID.randomUUID();
        actingUserId = UUID.randomUUID();

        legalEntity = LegalEntity.builder().id(legalEntityId).code("LE-01").name("LE One")
                .accountingStandard(AccountingStandard.IND_AS).build();
        calendar = AccountingCalendar.builder().id(calendarId).name("FY Calendar").build();

        lenient().when(configService.getOrDefault(legalEntityId)).thenReturn(defaultConfig());
        lenient().when(periodStatusRepository.countByLegalEntityIdAndStatus(any(), any())).thenReturn(0L);
        lenient().when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(any()))
                .thenReturn(List.of());
        lenient().when(periodStatusRepository.findFirstByLegalEntityIdAndStatusOrderByAccountingPeriod_StartDateAsc(any(), any()))
                .thenReturn(Optional.empty());
    }

    // ---- fixtures ----

    private LegalEntityPeriodConfig defaultConfig() {
        return LegalEntityPeriodConfig.builder().maxOpenPeriods(2).futurePeriodLimit(1)
                .adjustmentPeriodEnabled(true).build();
    }

    private AccountingPeriod period(String name, int periodNumber, String fiscalYear,
                                     AccountingPeriodType type, LocalDate start, LocalDate end) {
        AccountingPeriod p = AccountingPeriod.builder()
                .accountingCalendar(calendar)
                .name(name)
                .periodNumber(periodNumber)
                .fiscalYear(fiscalYear)
                .periodType(type)
                .quarterNumber(1)
                .startDate(start)
                .endDate(end)
                .build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private PeriodStatus periodStatus(AccountingPeriod period, PeriodStatusEnum status) {
        PeriodStatus ps = PeriodStatus.builder().legalEntity(legalEntity).accountingPeriod(period).status(status).build();
        ps.setId(UUID.randomUUID());
        return ps;
    }

    private PeriodStatusResponse dummyResponse(PeriodStatusEnum status) {
        return new PeriodStatusResponse(UUID.randomUUID(), legalEntityId, "LE One", UUID.randomUUID(), "P",
                "2025-26", status, null, null, null, null, null, null, null, null);
    }

    private void grantRole(String roleCode) {
        UserRole ur = UserRole.builder().role(Role.builder().code(roleCode).build()).build();
        when(userRoleRepository.findByUserIdAndLegalEntityId(actingUserId, legalEntityId)).thenReturn(List.of(ur));
    }

    // ---- Rule 1 — Max Open Periods ----

    @Test
    void testMaxOpenPeriods_exceedsLimit_rejects() {
        AccountingPeriod requested = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(requested, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusRepository.countByLegalEntityIdAndStatus(legalEntityId, PeriodStatusEnum.OPEN)).thenReturn(2L);

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MAX_OPEN_PERIODS_EXCEEDED");

        verify(periodStatusService, never()).open(any(), any());
    }

    @Test
    void testMaxOpenPeriods_withinLimit_succeeds() {
        AccountingPeriod requested = period("APR-2025", 1, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        PeriodStatus ps = periodStatus(requested, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusRepository.countByLegalEntityIdAndStatus(legalEntityId, PeriodStatusEnum.OPEN)).thenReturn(1L);
        when(accountingPeriodRepository.findTopByAccountingCalendarIdAndPeriodTypeAndEndDateLessThanOrderByEndDateDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        when(periodStatusService.open(ps.getId(), "prashanth")).thenReturn(dummyResponse(PeriodStatusEnum.OPEN));

        PeriodStatusResponse result = service.open(ps.getId(), actingUserId, "prashanth");

        assertThat(result.status()).isEqualTo(PeriodStatusEnum.OPEN);
        verify(periodStatusService).open(ps.getId(), "prashanth");
    }

    // ---- Rule 2 — Future Period Limit ----

    @Test
    void testFuturePeriodLimit_tooFarAhead_rejects() {
        AccountingPeriod p1 = period("APR-2025", 1, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        AccountingPeriod p2 = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        AccountingPeriod p3 = period("JUN-2025", 3, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));
        AccountingPeriod p4 = period("JUL-2025", 4, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 31));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(p1, p2, p3, p4));

        PeriodStatus p1Open = periodStatus(p1, PeriodStatusEnum.OPEN);
        when(periodStatusRepository.findFirstByLegalEntityIdAndStatusOrderByAccountingPeriod_StartDateAsc(
                legalEntityId, PeriodStatusEnum.OPEN)).thenReturn(Optional.of(p1Open));

        PeriodStatus ps = periodStatus(p4, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "FUTURE_PERIOD_LIMIT_EXCEEDED");
    }

    @Test
    void testFuturePeriodLimit_withinLimit_succeeds() {
        AccountingPeriod p1 = period("APR-2025", 1, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        AccountingPeriod p2 = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(p1, p2));

        PeriodStatus p1Open = periodStatus(p1, PeriodStatusEnum.OPEN);
        when(periodStatusRepository.findFirstByLegalEntityIdAndStatusOrderByAccountingPeriod_StartDateAsc(
                legalEntityId, PeriodStatusEnum.OPEN)).thenReturn(Optional.of(p1Open));
        when(periodStatusRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, p1.getId()))
                .thenReturn(Optional.of(p1Open));
        when(accountingPeriodRepository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendarId, "2025-26", 1))
                .thenReturn(Optional.of(p1));

        PeriodStatus ps = periodStatus(p2, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusService.open(ps.getId(), "prashanth")).thenReturn(dummyResponse(PeriodStatusEnum.OPEN));

        PeriodStatusResponse result = service.open(ps.getId(), actingUserId, "prashanth");

        assertThat(result.status()).isEqualTo(PeriodStatusEnum.OPEN);
    }

    // ---- Rule 3a — Opening Sequence ----

    @Test
    void testOpeningSequence_priorNotOpened_rejects() {
        AccountingPeriod p1 = period("APR-2025", 1, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        AccountingPeriod p2 = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));

        when(accountingPeriodRepository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendarId, "2025-26", 1))
                .thenReturn(Optional.of(p1));
        when(periodStatusRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, p1.getId()))
                .thenReturn(Optional.empty()); // never opened -> NOT_OPENED

        PeriodStatus ps = periodStatus(p2, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "OPENING_SEQUENCE_VIOLATION");
    }

    @Test
    void testOpeningSequence_priorOpened_succeeds() {
        AccountingPeriod p1 = period("APR-2025", 1, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        AccountingPeriod p2 = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));

        when(accountingPeriodRepository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendarId, "2025-26", 1))
                .thenReturn(Optional.of(p1));
        when(periodStatusRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, p1.getId()))
                .thenReturn(Optional.of(periodStatus(p1, PeriodStatusEnum.CLOSED)));

        PeriodStatus ps = periodStatus(p2, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusService.open(ps.getId(), "prashanth")).thenReturn(dummyResponse(PeriodStatusEnum.OPEN));

        PeriodStatusResponse result = service.open(ps.getId(), actingUserId, "prashanth");

        assertThat(result.status()).isEqualTo(PeriodStatusEnum.OPEN);
    }

    // ---- Rule 3b — Closing Sequence ----

    @Test
    void testClosingSequence_priorStillOpen_rejects() {
        AccountingPeriod p2 = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(p2, PeriodStatusEnum.OPEN);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        AccountingPeriod p1 = period("APR-2025", 1, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        when(periodStatusRepository.findOpenPriorPeriods(legalEntityId, "2025-26", 2))
                .thenReturn(List.of(periodStatus(p1, PeriodStatusEnum.OPEN)));

        assertThatThrownBy(() -> service.close(ps.getId(), "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "CLOSING_SEQUENCE_VIOLATION");

        verify(periodStatusService, never()).close(any(), any());
    }

    @Test
    void testClosingSequence_allPriorClosed_succeeds() {
        AccountingPeriod p2 = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(p2, PeriodStatusEnum.OPEN);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusRepository.findOpenPriorPeriods(legalEntityId, "2025-26", 2)).thenReturn(List.of());
        when(periodStatusService.close(ps.getId(), "prashanth")).thenReturn(dummyResponse(PeriodStatusEnum.CLOSED));

        PeriodStatusResponse result = service.close(ps.getId(), "prashanth");

        assertThat(result.status()).isEqualTo(PeriodStatusEnum.CLOSED);
    }

    // ---- Rule 4 — Cross Fiscal Year ----

    @Test
    void testCrossFiscalYear_adjNotClosed_rejects() {
        AccountingPeriod mar2026 = period("MAR-2026", 12, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        AccountingPeriod adj2026 = period("ADJ-2026", 13, "2025-26", AccountingPeriodType.ADJUSTMENT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15));
        AccountingPeriod apr2026 = period("APR-2026", 1, "2026-27", AccountingPeriodType.REGULAR,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));

        PeriodStatus ps = periodStatus(apr2026, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        when(accountingPeriodRepository.findTopByAccountingCalendarIdAndPeriodTypeAndEndDateLessThanOrderByEndDateDesc(
                calendarId, AccountingPeriodType.REGULAR, apr2026.getStartDate())).thenReturn(Optional.of(mar2026));
        when(accountingPeriodRepository.findByAccountingCalendarIdAndFiscalYearAndPeriodType(
                calendarId, "2025-26", AccountingPeriodType.ADJUSTMENT)).thenReturn(Optional.of(adj2026));
        when(periodStatusRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, adj2026.getId()))
                .thenReturn(Optional.of(periodStatus(adj2026, PeriodStatusEnum.CLOSED))); // closed, not PERMANENTLY_CLOSED

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "FISCAL_YEAR_NOT_CLOSED");
    }

    // ---- Rule 5 — Adjustment Period ----

    @Test
    void testAdjPeriod_openBeforeLastRegularClosed_rejects() {
        AccountingPeriod mar2026 = period("MAR-2026", 12, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        AccountingPeriod adj2026 = period("ADJ-2026", 13, "2025-26", AccountingPeriodType.ADJUSTMENT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15));

        PeriodStatus ps = periodStatus(adj2026, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        when(accountingPeriodRepository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendarId, "2025-26", 12))
                .thenReturn(Optional.of(mar2026));
        when(periodStatusRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, mar2026.getId()))
                .thenReturn(Optional.of(periodStatus(mar2026, PeriodStatusEnum.OPEN))); // opened but not yet closed
        when(accountingPeriodRepository.findTopByAccountingCalendarIdAndFiscalYearAndPeriodTypeOrderByPeriodNumberDesc(
                calendarId, "2025-26", AccountingPeriodType.REGULAR)).thenReturn(Optional.of(mar2026));

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "ADJ_PERIOD_PRIOR_NOT_CLOSED");
    }

    @Test
    void testAdjPeriod_nonManager_rejects() {
        AccountingPeriod mar2026 = period("MAR-2026", 12, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        AccountingPeriod adj2026 = period("ADJ-2026", 13, "2025-26", AccountingPeriodType.ADJUSTMENT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 15));

        PeriodStatus ps = periodStatus(adj2026, PeriodStatusEnum.NOT_OPENED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        when(accountingPeriodRepository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(calendarId, "2025-26", 12))
                .thenReturn(Optional.of(mar2026));
        when(periodStatusRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, mar2026.getId()))
                .thenReturn(Optional.of(periodStatus(mar2026, PeriodStatusEnum.CLOSED)));
        when(accountingPeriodRepository.findTopByAccountingCalendarIdAndFiscalYearAndPeriodTypeOrderByPeriodNumberDesc(
                calendarId, "2025-26", AccountingPeriodType.REGULAR)).thenReturn(Optional.of(mar2026));

        grantRole("GL_ACCOUNTANT");

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MANAGER_ROLE_REQUIRED");
    }

    // ---- Rule 6 — Reopen ----

    @Test
    void testReopen_permanentlyClosed_rejects() {
        AccountingPeriod period = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(period, PeriodStatusEnum.PERMANENTLY_CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "REOPEN_NOT_ALLOWED");

        verify(periodStatusService, never()).reopen(any(), any());
    }

    @Test
    void testReopen_priorFiscalYear_rejects() {
        LocalDate today = LocalDate.now();
        AccountingPeriod currentPeriod = period("CURRENT", 6, "2025-26", AccountingPeriodType.REGULAR,
                today.minusDays(5), today.plusDays(5));
        AccountingPeriod oldPeriod = period("MAY-2024", 2, "2024-25", AccountingPeriodType.REGULAR,
                LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(oldPeriod, currentPeriod));

        PeriodStatus ps = periodStatus(oldPeriod, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "REOPEN_PRIOR_FISCAL_YEAR");
    }

    @Test
    void testReopen_closedCurrentFY_succeeds() {
        LocalDate today = LocalDate.now();
        AccountingPeriod currentPeriod = period("CURRENT", 6, "2025-26", AccountingPeriodType.REGULAR,
                today.minusDays(5), today.plusDays(5));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(currentPeriod));

        PeriodStatus ps = periodStatus(currentPeriod, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusService.reopen(ps.getId(), "prashanth")).thenReturn(dummyResponse(PeriodStatusEnum.OPEN));

        grantRole("GL_MANAGER");

        PeriodStatusResponse result = service.open(ps.getId(), actingUserId, "prashanth");

        assertThat(result.status()).isEqualTo(PeriodStatusEnum.OPEN);
        verify(periodStatusService).reopen(ps.getId(), "prashanth");
    }

    @Test
    void testReopen_viaOpen_maxOpenPeriodsExceeded_rejects() {
        // Bug fix regression test: reopening a CLOSED period through the /open endpoint
        // must be counted against Rule 1 exactly like a fresh open — it used to bypass
        // validateMaxOpenPeriods() entirely by branching straight into doReopen().
        LocalDate today = LocalDate.now();
        AccountingPeriod currentPeriod = period("CURRENT", 6, "2025-26", AccountingPeriodType.REGULAR,
                today.minusDays(5), today.plusDays(5));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(currentPeriod));

        PeriodStatus ps = periodStatus(currentPeriod, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusRepository.countByLegalEntityIdAndStatus(legalEntityId, PeriodStatusEnum.OPEN)).thenReturn(2L);

        grantRole("GL_MANAGER");

        assertThatThrownBy(() -> service.open(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MAX_OPEN_PERIODS_EXCEEDED");

        verify(periodStatusService, never()).reopen(any(), any());
    }

    @Test
    void testReopenEndpoint_maxOpenPeriodsExceeded_rejects() {
        // Same bug, exercised via the dedicated /reopen endpoint.
        LocalDate today = LocalDate.now();
        AccountingPeriod currentPeriod = period("CURRENT", 6, "2025-26", AccountingPeriodType.REGULAR,
                today.minusDays(5), today.plusDays(5));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(currentPeriod));

        PeriodStatus ps = periodStatus(currentPeriod, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusRepository.countByLegalEntityIdAndStatus(legalEntityId, PeriodStatusEnum.OPEN)).thenReturn(2L);

        grantRole("GL_MANAGER");

        assertThatThrownBy(() -> service.reopen(ps.getId(), actingUserId, "prashanth", "Late invoice correction"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MAX_OPEN_PERIODS_EXCEEDED");

        verify(periodStatusService, never()).reopen(any(), any());
    }

    // ---- Rule 6 — dedicated reopen(id, actingUserId, reopenedBy, reason) endpoint ----

    @Test
    void testReopenEndpoint_closedCurrentFY_succeedsAndLogsReason() {
        LocalDate today = LocalDate.now();
        AccountingPeriod currentPeriod = period("CURRENT", 6, "2025-26", AccountingPeriodType.REGULAR,
                today.minusDays(5), today.plusDays(5));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(currentPeriod));

        PeriodStatus ps = periodStatus(currentPeriod, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusService.reopen(ps.getId(), "prashanth")).thenReturn(dummyResponse(PeriodStatusEnum.OPEN));

        grantRole("GL_MANAGER");

        PeriodStatusResponse result = service.reopen(ps.getId(), actingUserId, "prashanth", "Late invoice correction");

        assertThat(result.status()).isEqualTo(PeriodStatusEnum.OPEN);
        verify(periodStatusService).reopen(ps.getId(), "prashanth");
        verify(auditService).log(any(), eq("period_status_reopen_reason"), eq(ps.getId()), any(),
                eq(java.util.Map.of("reason", "Late invoice correction")), eq("prashanth"));
    }

    @Test
    void testReopenEndpoint_noReasonGiven_skipsReasonAuditLog() {
        LocalDate today = LocalDate.now();
        AccountingPeriod currentPeriod = period("CURRENT", 6, "2025-26", AccountingPeriodType.REGULAR,
                today.minusDays(5), today.plusDays(5));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(currentPeriod));

        PeriodStatus ps = periodStatus(currentPeriod, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusService.reopen(ps.getId(), "prashanth")).thenReturn(dummyResponse(PeriodStatusEnum.OPEN));

        grantRole("GL_MANAGER");

        service.reopen(ps.getId(), actingUserId, "prashanth", null);

        verify(auditService, never()).log(any(), eq("period_status_reopen_reason"), any(), any(), any(), any());
    }

    @Test
    void testReopenEndpoint_permanentlyClosed_rejects() {
        AccountingPeriod requested = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(requested, PeriodStatusEnum.PERMANENTLY_CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        assertThatThrownBy(() -> service.reopen(ps.getId(), actingUserId, "prashanth", "why not"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "REOPEN_NOT_ALLOWED");

        verify(periodStatusService, never()).reopen(any(), any());
    }

    @Test
    void testReopenEndpoint_nonManager_rejects() {
        LocalDate today = LocalDate.now();
        AccountingPeriod currentPeriod = period("CURRENT", 6, "2025-26", AccountingPeriodType.REGULAR,
                today.minusDays(5), today.plusDays(5));

        when(accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId))
                .thenReturn(List.of(currentPeriod));

        PeriodStatus ps = periodStatus(currentPeriod, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        grantRole("GL_ACCOUNTANT");

        assertThatThrownBy(() -> service.reopen(ps.getId(), actingUserId, "prashanth", "why not"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MANAGER_ROLE_REQUIRED");

        verify(periodStatusService, never()).reopen(any(), any());
    }

    // ---- Rule 7 — Permanent Close ----

    @Test
    void testPermanentClose_alreadyPermanent_rejects() {
        AccountingPeriod period = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(period, PeriodStatusEnum.PERMANENTLY_CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        assertThatThrownBy(() -> service.permanentlyClose(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "ALREADY_PERMANENTLY_CLOSED");

        verify(periodStatusService, never()).permanentlyClose(any(), any());
    }

    @Test
    void testPermanentClose_requiresClosed_rejects() {
        AccountingPeriod period = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(period, PeriodStatusEnum.OPEN);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));

        assertThatThrownBy(() -> service.permanentlyClose(ps.getId(), actingUserId, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "PERMANENT_CLOSE_REQUIRES_CLOSED");
    }

    @Test
    void testPermanentClose_managerOrAbove_succeeds() {
        AccountingPeriod period = period("MAY-2025", 2, "2025-26", AccountingPeriodType.REGULAR,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 31));
        PeriodStatus ps = periodStatus(period, PeriodStatusEnum.CLOSED);
        when(periodStatusRepository.findById(ps.getId())).thenReturn(Optional.of(ps));
        when(periodStatusService.permanentlyClose(ps.getId(), "prashanth"))
                .thenReturn(dummyResponse(PeriodStatusEnum.PERMANENTLY_CLOSED));

        grantRole("SYS_ADMIN");

        PeriodStatusResponse result = service.permanentlyClose(ps.getId(), actingUserId, "prashanth");

        assertThat(result.status()).isEqualTo(PeriodStatusEnum.PERMANENTLY_CLOSED);
    }
}
