package com.evyoog.gl.periodmanagement.service;

import com.evyoog.gl.auth.repository.UserRoleRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.domain.AccountingPeriodType;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.periodmanagement.domain.LegalEntityPeriodConfig;
import com.evyoog.gl.periodstatus.domain.PeriodStatus;
import com.evyoog.gl.periodstatus.domain.PeriodStatusEnum;
import com.evyoog.gl.periodstatus.dto.PeriodStatusResponse;
import com.evyoog.gl.periodstatus.repository.PeriodStatusRepository;
import com.evyoog.gl.periodstatus.service.PeriodStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * V33 Period Management Controls — the rules layer sitting in front of
 * {@link PeriodStatusService}'s raw OPEN/CLOSE/REOPEN/PERMANENTLY_CLOSE
 * transitions. {@code PeriodStatusService} keeps performing (and guarding)
 * the actual status mutation and audit write; this service only decides
 * whether a requested mutation is allowed to happen at all, per Rules 1-7.
 *
 * <p>Roles are checked here (not merely via {@code @PreAuthorize}) because
 * {@code gl:period:manage} — already GL_MANAGER/SYS_ADMIN-only per the
 * AUTH-01 permission seed — gates the controller endpoint as a whole, but
 * Rules 5b/6c/7b specifically require "GL_MANAGER or above", independent of
 * whatever permission set a role happens to hold. Looking the caller's
 * actual role up via {@link UserRoleRepository} keeps that requirement
 * correct even if a future role gains {@code gl:period:manage} without also
 * being manager-or-above.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PeriodManagementService {

    private static final Set<String> MANAGER_OR_ABOVE_ROLES = Set.of("SYS_ADMIN", "GL_MANAGER");

    private final PeriodStatusRepository periodStatusRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final UserRoleRepository userRoleRepository;
    private final LegalEntityPeriodConfigService configService;
    private final PeriodStatusService periodStatusService;
    private final AuditService auditService;

    /**
     * Backs {@code POST /period-status/{id}/open}. A CLOSED period is a
     * reopen (Rule 6); anything else is a fresh open (Rules 1, 2, 3a, 4, 5).
     */
    @Transactional
    public PeriodStatusResponse open(UUID periodStatusId, UUID actingUserId, String performedBy) {
        PeriodStatus ps = findOrThrow(periodStatusId);
        UUID legalEntityId = ps.getLegalEntity().getId();
        AccountingPeriod period = ps.getAccountingPeriod();

        if (ps.getStatus() == PeriodStatusEnum.CLOSED || ps.getStatus() == PeriodStatusEnum.PERMANENTLY_CLOSED) {
            // Implicit reopen via the open() endpoint — same rules as the dedicated
            // reopen() below, just with no reason to record.
            return doReopen(ps, actingUserId, performedBy, null);
        }

        LegalEntityPeriodConfig config = configService.getOrDefault(legalEntityId);
        validateMaxOpenPeriods(legalEntityId, config);
        validateFuturePeriodLimit(period, legalEntityId, config);
        validateOpeningSequence(period, legalEntityId);
        validateCrossFiscalYear(period, legalEntityId, config);
        validateAdjustmentPeriodOpen(period, legalEntityId, actingUserId, config);

        return periodStatusService.open(periodStatusId, performedBy);
    }

    /**
     * Backs the dedicated {@code POST /period-status/{id}/reopen} endpoint — Rule 6,
     * explicit reopen with a caller-supplied {@code reason} recorded to the audit trail.
     * Same guards as the implicit reopen inside {@link #open}; {@code performedBy} here
     * is the request body's {@code reopenedBy} rather than the {@code X-User-Id} header,
     * per this endpoint's own request shape.
     */
    @Transactional
    public PeriodStatusResponse reopen(UUID periodStatusId, UUID actingUserId, String reopenedBy, String reason) {
        PeriodStatus ps = findOrThrow(periodStatusId);
        return doReopen(ps, actingUserId, reopenedBy, reason);
    }

    private PeriodStatusResponse doReopen(PeriodStatus ps, UUID actingUserId, String performedBy, String reason) {
        UUID legalEntityId = ps.getLegalEntity().getId();
        AccountingPeriod period = ps.getAccountingPeriod();

        // validateReopen() itself rejects the PERMANENTLY_CLOSED case (Rule 6a) before
        // periodStatusService.reopen() is ever called; a non-CLOSED, non-PERMANENTLY_CLOSED
        // status (e.g. OPEN) falls through to periodStatusService.reopen()'s own
        // INVALID_PERIOD_TRANSITION guard.
        validateReopen(ps, period, actingUserId, legalEntityId);

        // Bug fix (September 2026): reopening a CLOSED period puts it back into OPEN
        // just like a fresh open does, so it must count against Rule 1 exactly the same
        // way — a reopen used to skip straight from open()'s CLOSED/PERMANENTLY_CLOSED
        // branch into doReopen() without ever calling validateMaxOpenPeriods(), letting a
        // caller silently exceed max_open_periods by reopening instead of opening fresh.
        LegalEntityPeriodConfig config = configService.getOrDefault(legalEntityId);
        validateMaxOpenPeriods(legalEntityId, config);

        PeriodStatusResponse response = periodStatusService.reopen(ps.getId(), performedBy);

        if (reason != null && !reason.isBlank()) {
            auditService.log(AuditAction.UPDATE, "period_status_reopen_reason", ps.getId(), null,
                    Map.of("reason", reason), performedBy);
        }
        return response;
    }

    /** Backs {@code POST /period-status/{id}/close} — Rule 3b. */
    @Transactional
    public PeriodStatusResponse close(UUID periodStatusId, String performedBy) {
        PeriodStatus ps = findOrThrow(periodStatusId);
        validateClosingSequence(ps);
        return periodStatusService.close(periodStatusId, performedBy);
    }

    /** Backs {@code POST /period-status/{id}/permanently-close} — Rule 7. */
    @Transactional
    public PeriodStatusResponse permanentlyClose(UUID periodStatusId, UUID actingUserId, String performedBy) {
        PeriodStatus ps = findOrThrow(periodStatusId);

        if (ps.getStatus() == PeriodStatusEnum.PERMANENTLY_CLOSED) {
            throw new EvyoogException("ALREADY_PERMANENTLY_CLOSED", "This period is already permanently closed.");
        }
        if (ps.getStatus() != PeriodStatusEnum.CLOSED) {
            throw new EvyoogException("PERMANENT_CLOSE_REQUIRES_CLOSED",
                    "Only a CLOSED period can be permanently closed. Current status: " + ps.getStatus() + ".");
        }
        requireManagerOrAbove(actingUserId, ps.getLegalEntity().getId(), "permanently close a period");

        return periodStatusService.permanentlyClose(periodStatusId, performedBy);
    }

    // ---- Rule 1 — Max Open Periods ----

    private void validateMaxOpenPeriods(UUID legalEntityId, LegalEntityPeriodConfig config) {
        long openCount = periodStatusRepository.countByLegalEntityIdAndStatus(legalEntityId, PeriodStatusEnum.OPEN);
        log.debug("Rule 1 (max open periods): legalEntityId={}, currentOpenCount={}, maxOpenPeriods={}",
                legalEntityId, openCount, config.getMaxOpenPeriods());
        if (openCount >= config.getMaxOpenPeriods()) {
            throw new EvyoogException("MAX_OPEN_PERIODS_EXCEEDED",
                    "Maximum open periods limit (" + config.getMaxOpenPeriods() +
                            ") reached. Close an existing period before opening a new one.");
        }
    }

    // ---- Rule 2 — Future Period Limit ----

    private void validateFuturePeriodLimit(AccountingPeriod requested, UUID legalEntityId, LegalEntityPeriodConfig config) {
        UUID calendarId = requested.getAccountingCalendar().getId();
        List<AccountingPeriod> ordered = accountingPeriodRepository.findByAccountingCalendarIdOrderByStartDateAsc(calendarId);

        AccountingPeriod baseline = resolveCurrentPeriod(legalEntityId, ordered);
        if (baseline == null) {
            return; // no determinable "current" period yet — nothing to compare against
        }

        int baselineIndex = indexOfById(ordered, baseline.getId());
        int requestedIndex = indexOfById(ordered, requested.getId());
        if (baselineIndex < 0 || requestedIndex < 0) {
            return;
        }

        int periodsAhead = requestedIndex - baselineIndex;
        if (periodsAhead > config.getFuturePeriodLimit()) {
            throw new EvyoogException("FUTURE_PERIOD_LIMIT_EXCEEDED",
                    "Cannot open a period more than " + config.getFuturePeriodLimit() +
                            " period(s) ahead of the current period.");
        }
    }

    private AccountingPeriod resolveCurrentPeriod(UUID legalEntityId, List<AccountingPeriod> ordered) {
        Optional<PeriodStatus> earliestOpen = periodStatusRepository
                .findFirstByLegalEntityIdAndStatusOrderByAccountingPeriod_StartDateAsc(legalEntityId, PeriodStatusEnum.OPEN);
        if (earliestOpen.isPresent()) {
            return earliestOpen.get().getAccountingPeriod();
        }

        LocalDate today = LocalDate.now();
        return ordered.stream()
                .filter(p -> !p.getStartDate().isAfter(today) && !p.getEndDate().isBefore(today))
                .findFirst()
                .orElse(null);
    }

    // ---- Rule 3a — Opening Sequence ----

    private void validateOpeningSequence(AccountingPeriod requested, UUID legalEntityId) {
        if (requested.getPeriodType() == AccountingPeriodType.REGULAR && requested.getPeriodNumber() == 1) {
            return; // Period 1 of a fiscal year — Rule 4 (cross fiscal year) applies instead
        }

        int priorNumber = requested.getPeriodNumber() - 1;
        Optional<AccountingPeriod> prior = accountingPeriodRepository.findByAccountingCalendarIdAndFiscalYearAndPeriodNumber(
                requested.getAccountingCalendar().getId(), requested.getFiscalYear(), priorNumber);

        if (prior.isEmpty()) {
            throw new EvyoogException("OPENING_SEQUENCE_VIOLATION",
                    "Period " + priorNumber + " must be opened before " + requested.getName() + " can be opened.");
        }

        PeriodStatusEnum priorStatus = statusOf(legalEntityId, prior.get().getId());
        if (priorStatus == PeriodStatusEnum.NOT_OPENED) {
            throw new EvyoogException("OPENING_SEQUENCE_VIOLATION",
                    "Period " + prior.get().getName() + " must be opened before " + requested.getName() + " can be opened.");
        }
    }

    // ---- Rule 3b — Closing Sequence ----

    private void validateClosingSequence(PeriodStatus ps) {
        AccountingPeriod period = ps.getAccountingPeriod();
        List<PeriodStatus> openPriorPeriods = periodStatusRepository.findOpenPriorPeriods(
                ps.getLegalEntity().getId(), period.getFiscalYear(), period.getPeriodNumber());

        if (!openPriorPeriods.isEmpty()) {
            throw new EvyoogException("CLOSING_SEQUENCE_VIOLATION",
                    "All prior periods must be closed before " + period.getName() + " can be closed.");
        }
    }

    // ---- Rule 4 — Cross Fiscal Year ----

    private void validateCrossFiscalYear(AccountingPeriod requested, UUID legalEntityId, LegalEntityPeriodConfig config) {
        if (!(requested.getPeriodType() == AccountingPeriodType.REGULAR && requested.getPeriodNumber() == 1)) {
            return; // only Period 1 of a fiscal year crosses a fiscal-year boundary
        }

        UUID calendarId = requested.getAccountingCalendar().getId();
        Optional<AccountingPeriod> lastRegularOfPriorFy = accountingPeriodRepository
                .findTopByAccountingCalendarIdAndPeriodTypeAndEndDateLessThanOrderByEndDateDesc(
                        calendarId, AccountingPeriodType.REGULAR, requested.getStartDate());

        if (lastRegularOfPriorFy.isEmpty()) {
            return; // first-ever fiscal year for this calendar — nothing prior to check
        }

        AccountingPeriod marPeriod = lastRegularOfPriorFy.get();
        String priorFiscalYear = marPeriod.getFiscalYear();

        if (Boolean.TRUE.equals(config.getAdjustmentPeriodEnabled())) {
            Optional<AccountingPeriod> adj = accountingPeriodRepository
                    .findByAccountingCalendarIdAndFiscalYearAndPeriodType(calendarId, priorFiscalYear, AccountingPeriodType.ADJUSTMENT);
            if (adj.isPresent()) {
                if (statusOf(legalEntityId, adj.get().getId()) != PeriodStatusEnum.PERMANENTLY_CLOSED) {
                    throw fiscalYearNotClosed(priorFiscalYear, requested.getFiscalYear());
                }
                return;
            }
            // adjustment enabled but no ADJ period exists yet for the prior FY — fall through to the MAR check below
        }

        PeriodStatusEnum marStatus = statusOf(legalEntityId, marPeriod.getId());
        if (marStatus != PeriodStatusEnum.CLOSED && marStatus != PeriodStatusEnum.PERMANENTLY_CLOSED) {
            throw fiscalYearNotClosed(priorFiscalYear, requested.getFiscalYear());
        }
    }

    private EvyoogException fiscalYearNotClosed(String priorFiscalYear, String nextFiscalYear) {
        return new EvyoogException("FISCAL_YEAR_NOT_CLOSED",
                "Current fiscal year " + priorFiscalYear + " must be fully closed before opening " + nextFiscalYear + ".");
    }

    // ---- Rule 5 — Adjustment Period Rules ----

    private void validateAdjustmentPeriodOpen(AccountingPeriod requested, UUID legalEntityId, UUID actingUserId,
                                                LegalEntityPeriodConfig config) {
        if (requested.getPeriodType() != AccountingPeriodType.ADJUSTMENT) {
            return;
        }

        if (!Boolean.TRUE.equals(config.getAdjustmentPeriodEnabled())) {
            throw new EvyoogException("ADJUSTMENT_PERIOD_DISABLED",
                    "Adjustment Period is disabled for this Legal Entity.");
        }

        Optional<AccountingPeriod> lastRegular = accountingPeriodRepository
                .findTopByAccountingCalendarIdAndFiscalYearAndPeriodTypeOrderByPeriodNumberDesc(
                        requested.getAccountingCalendar().getId(), requested.getFiscalYear(), AccountingPeriodType.REGULAR);

        if (lastRegular.isPresent()) {
            PeriodStatusEnum status = statusOf(legalEntityId, lastRegular.get().getId());
            if (status != PeriodStatusEnum.CLOSED && status != PeriodStatusEnum.PERMANENTLY_CLOSED) {
                throw new EvyoogException("ADJ_PERIOD_PRIOR_NOT_CLOSED",
                        "Period " + lastRegular.get().getName() + " must be closed before the Adjustment Period can be opened.");
            }
        }

        requireManagerOrAbove(actingUserId, legalEntityId, "open the Adjustment Period");
    }

    // ---- Rule 6 — Reopen ----

    private void validateReopen(PeriodStatus ps, AccountingPeriod period, UUID actingUserId, UUID legalEntityId) {
        if (ps.getStatus() == PeriodStatusEnum.PERMANENTLY_CLOSED) {
            throw new EvyoogException("REOPEN_NOT_ALLOWED", "Permanently closed periods cannot be reopened.");
        }
        if (!isCurrentFiscalYear(period)) {
            throw new EvyoogException("REOPEN_PRIOR_FISCAL_YEAR", "Periods from prior fiscal years cannot be reopened.");
        }
        requireManagerOrAbove(actingUserId, legalEntityId, "reopen a period");
    }

    private boolean isCurrentFiscalYear(AccountingPeriod period) {
        LocalDate today = LocalDate.now();
        List<AccountingPeriod> ordered = accountingPeriodRepository
                .findByAccountingCalendarIdOrderByStartDateAsc(period.getAccountingCalendar().getId());

        return ordered.stream()
                .filter(p -> !p.getStartDate().isAfter(today) && !p.getEndDate().isBefore(today))
                .map(AccountingPeriod::getFiscalYear)
                .findFirst()
                .map(fy -> fy.equals(period.getFiscalYear()))
                .orElse(false);
    }

    // ---- shared helpers ----

    private void requireManagerOrAbove(UUID actingUserId, UUID legalEntityId, String action) {
        boolean isManagerOrAbove = actingUserId != null
                && userRoleRepository.findByUserIdAndLegalEntityId(actingUserId, legalEntityId).stream()
                        .anyMatch(ur -> MANAGER_OR_ABOVE_ROLES.contains(ur.getRole().getCode()));
        if (!isManagerOrAbove) {
            throw new EvyoogException("MANAGER_ROLE_REQUIRED", "Only GL Managers can " + action + ".", HttpStatus.FORBIDDEN);
        }
    }

    private PeriodStatusEnum statusOf(UUID legalEntityId, UUID accountingPeriodId) {
        return periodStatusRepository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, accountingPeriodId)
                .map(PeriodStatus::getStatus)
                .orElse(PeriodStatusEnum.NOT_OPENED);
    }

    private int indexOfById(List<AccountingPeriod> periods, UUID id) {
        for (int i = 0; i < periods.size(); i++) {
            if (periods.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private PeriodStatus findOrThrow(UUID id) {
        return periodStatusRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PeriodStatus", id));
    }
}
