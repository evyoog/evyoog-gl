package com.evyoog.gl.periodstatus.service;

import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.period.domain.AccountingPeriod;
import com.evyoog.gl.period.repository.AccountingPeriodRepository;
import com.evyoog.gl.periodstatus.domain.PeriodStatus;
import com.evyoog.gl.periodstatus.domain.PeriodStatusEnum;
import com.evyoog.gl.periodstatus.dto.CreatePeriodStatusRequest;
import com.evyoog.gl.periodstatus.dto.PeriodStatusResponse;
import com.evyoog.gl.periodstatus.mapper.PeriodStatusMapper;
import com.evyoog.gl.periodstatus.repository.PeriodStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The GATE for journal posting. GL-15's Posting Engine calls
 * {@link #validatePeriodOpen(UUID, UUID)} before every journal post, so any
 * change here has module-wide blast radius.
 */
@Service
@RequiredArgsConstructor
public class PeriodStatusService {

    /**
     * NOTE: CLOSED -> OPEN (reopen) is deliberately NOT modelled here — it would
     * make {@link #open(UUID, String)} silently accept reopening a CLOSED period
     * with none of the V33 Rule 6 guards (manager-or-above, current-fiscal-year-
     * only, not-permanently-closed). Reopening has its own dedicated method,
     * {@link #reopen(UUID, String)}, called only from
     * {@code PeriodManagementService} after those guards pass. Existing OPEN/
     * CLOSED/LOCKED transitions and their tests are unchanged by V33.
     */
    private static final Map<PeriodStatusEnum, Set<PeriodStatusEnum>> VALID_TRANSITIONS = Map.of(
            PeriodStatusEnum.NOT_OPENED, Set.of(PeriodStatusEnum.FUTURE_ENTERABLE, PeriodStatusEnum.OPEN),
            PeriodStatusEnum.FUTURE_ENTERABLE, Set.of(PeriodStatusEnum.OPEN),
            PeriodStatusEnum.OPEN, Set.of(PeriodStatusEnum.CLOSED),
            PeriodStatusEnum.CLOSED, Set.of(PeriodStatusEnum.LOCKED, PeriodStatusEnum.PERMANENTLY_CLOSED),
            PeriodStatusEnum.LOCKED, Set.of(),
            PeriodStatusEnum.PERMANENTLY_CLOSED, Set.of()
    );

    private final PeriodStatusRepository repository;
    private final LegalEntityRepository legalEntityRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final PeriodStatusMapper mapper;
    private final AuditService auditService;

    @Transactional
    public PeriodStatusResponse create(CreatePeriodStatusRequest request, String performedBy) {
        LegalEntity legalEntity = legalEntityRepository.findById(request.legalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", request.legalEntityId()));
        AccountingPeriod period = accountingPeriodRepository.findById(request.accountingPeriodId())
                .orElseThrow(() -> new ResourceNotFoundException("AccountingPeriod", request.accountingPeriodId()));

        rejectEventOnly(resolveLedger(request.legalEntityId()));

        if (repository.existsByLegalEntityIdAndAccountingPeriodId(request.legalEntityId(), request.accountingPeriodId())) {
            throw new EvyoogException("PERIOD_STATUS_EXISTS",
                    "Period status already exists for this Legal Entity and Period.");
        }

        PeriodStatus entity = PeriodStatus.builder()
                .legalEntity(legalEntity)
                .accountingPeriod(period)
                .status(PeriodStatusEnum.NOT_OPENED)
                .createdBy(performedBy)
                .updatedBy(performedBy)
                .build();

        PeriodStatus saved = repository.saveAndFlush(entity);
        auditService.log(AuditAction.CREATE, "period_status", saved.getId(), null, mapper.toResponse(saved), performedBy);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PeriodStatusResponse getById(UUID id) {
        return mapper.toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<PeriodStatusResponse> list(UUID legalEntityId, UUID accountingPeriodId, PeriodStatusEnum status) {
        List<PeriodStatus> results;
        if (accountingPeriodId != null) {
            results = repository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, accountingPeriodId)
                    .map(List::of).orElse(List.of());
        } else if (status != null) {
            results = repository.findByLegalEntityIdAndStatus(legalEntityId, status);
        } else {
            results = repository.findByLegalEntityId(legalEntityId);
        }
        return results.stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public PeriodStatusResponse open(UUID id, String performedBy) {
        return transition(id, PeriodStatusEnum.OPEN, performedBy);
    }

    @Transactional
    public PeriodStatusResponse futureEnterable(UUID id, String performedBy) {
        return transition(id, PeriodStatusEnum.FUTURE_ENTERABLE, performedBy);
    }

    @Transactional
    public PeriodStatusResponse close(UUID id, String performedBy) {
        return transition(id, PeriodStatusEnum.CLOSED, performedBy);
    }

    @Transactional
    public PeriodStatusResponse lock(UUID id, String performedBy) {
        return transition(id, PeriodStatusEnum.LOCKED, performedBy);
    }

    /**
     * V33 Rule 7 — finalise a CLOSED period so it can never be posted to or
     * reopened again. Goes through the same {@link #transition(UUID, PeriodStatusEnum, String)}
     * gate as every other transition, so only a CLOSED period may be
     * permanently closed (see {@code VALID_TRANSITIONS}); {@code PeriodManagementService}
     * additionally checks for the already-PERMANENTLY_CLOSED case up front to
     * surface a domain-specific error code instead of the generic
     * INVALID_PERIOD_TRANSITION.
     */
    @Transactional
    public PeriodStatusResponse permanentlyClose(UUID id, String performedBy) {
        return transition(id, PeriodStatusEnum.PERMANENTLY_CLOSED, performedBy);
    }

    /**
     * V33 Rule 6 — reopen a CLOSED period back to OPEN. Deliberately bypasses
     * {@code VALID_TRANSITIONS}/{@link #transition(UUID, PeriodStatusEnum, String)}
     * (see the comment on that map) — all of Rule 6's guards (manager-or-above,
     * current-fiscal-year-only, not-permanently-closed) are enforced by
     * {@code PeriodManagementService} before this is called.
     */
    @Transactional
    public PeriodStatusResponse reopen(UUID id, String performedBy) {
        PeriodStatus ps = findOrThrow(id);
        Ledger ledger = resolveLedger(ps.getLegalEntity().getId());
        rejectEventOnly(ledger);

        if (ps.getStatus() != PeriodStatusEnum.CLOSED) {
            throw new EvyoogException("INVALID_PERIOD_TRANSITION",
                    "Cannot reopen period from status " + ps.getStatus() + ". Only a CLOSED period can be reopened.");
        }

        PeriodStatusResponse before = mapper.toResponse(ps);
        ps.setStatus(PeriodStatusEnum.OPEN);
        ps.setOpenedAt(Instant.now());
        ps.setOpenedBy(performedBy);
        ps.setUpdatedBy(performedBy);

        PeriodStatus saved = repository.saveAndFlush(ps);
        auditService.log(AuditAction.UPDATE, "period_status", saved.getId(), before, mapper.toResponse(saved), performedBy);
        return mapper.toResponse(saved);
    }

    /**
     * Called by GL-15's Posting Engine before every journal post. EVENT_ONLY
     * Ledgers skip the gate entirely (Phase 1 has no period control for them).
     */
    @Transactional(readOnly = true)
    public void validatePeriodOpen(UUID legalEntityId, UUID accountingPeriodId) {
        Ledger ledger = resolveLedger(legalEntityId);
        if (ledger.getFinanceMode() == FinanceMode.EVENT_ONLY) {
            return;
        }

        PeriodStatus ps = repository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, accountingPeriodId)
                .orElseThrow(() -> new EvyoogException("PERIOD_NOT_OPEN",
                        "This period has not been opened for posting. Open the period before posting journals."));

        if (ps.getStatus() != PeriodStatusEnum.OPEN && ps.getStatus() != PeriodStatusEnum.FUTURE_ENTERABLE) {
            throw new EvyoogException("PERIOD_NOT_OPEN",
                    "Period is " + ps.getStatus() + ". Only OPEN or FUTURE_ENTERABLE periods accept journal postings.");
        }
    }

    /**
     * Auto-creates a NOT_OPENED row on first access, per GL-10 Rule 3. Not wired
     * to a controller endpoint today — available for future callers that resolve
     * status by (legalEntityId, accountingPeriodId) rather than by row id.
     */
    @Transactional
    public PeriodStatus getOrCreateStatus(UUID legalEntityId, UUID accountingPeriodId, String performedBy) {
        return repository.findByLegalEntityIdAndAccountingPeriodId(legalEntityId, accountingPeriodId)
                .orElseGet(() -> {
                    LegalEntity legalEntity = legalEntityRepository.findById(legalEntityId)
                            .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", legalEntityId));
                    AccountingPeriod period = accountingPeriodRepository.findById(accountingPeriodId)
                            .orElseThrow(() -> new ResourceNotFoundException("AccountingPeriod", accountingPeriodId));

                    PeriodStatus newPs = PeriodStatus.builder()
                            .legalEntity(legalEntity)
                            .accountingPeriod(period)
                            .status(PeriodStatusEnum.NOT_OPENED)
                            .createdBy(performedBy)
                            .updatedBy(performedBy)
                            .build();

                    PeriodStatus saved = repository.saveAndFlush(newPs);
                    auditService.log(AuditAction.CREATE, "period_status", saved.getId(), null,
                            mapper.toResponse(saved), performedBy);
                    return saved;
                });
    }

    private PeriodStatusResponse transition(UUID id, PeriodStatusEnum requested, String performedBy) {
        PeriodStatus ps = findOrThrow(id);
        Ledger ledger = resolveLedger(ps.getLegalEntity().getId());
        rejectEventOnly(ledger);

        PeriodStatusResponse before = mapper.toResponse(ps);
        validateTransition(ps.getStatus(), requested, ledger.getFinanceMode());

        Instant now = Instant.now();
        switch (requested) {
            case OPEN -> {
                ps.setOpenedAt(now);
                ps.setOpenedBy(performedBy);
            }
            case CLOSED -> {
                ps.setClosedAt(now);
                ps.setClosedBy(performedBy);
            }
            case LOCKED -> {
                ps.setLockedAt(now);
                ps.setLockedBy(performedBy);
            }
            case FUTURE_ENTERABLE, NOT_OPENED, PERMANENTLY_CLOSED -> {
                // no dedicated timestamp column for these states
            }
        }
        ps.setStatus(requested);
        ps.setUpdatedBy(performedBy);

        PeriodStatus saved = repository.saveAndFlush(ps);
        auditService.log(AuditAction.UPDATE, "period_status", saved.getId(), before, mapper.toResponse(saved), performedBy);
        return mapper.toResponse(saved);
    }

    private void validateTransition(PeriodStatusEnum current, PeriodStatusEnum requested, FinanceMode mode) {
        Set<PeriodStatusEnum> allowed = VALID_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(requested)) {
            throw new EvyoogException("INVALID_PERIOD_TRANSITION",
                    "Cannot transition period from " + current + " to " + requested +
                            ". Valid transitions from " + current + ": " + allowed);
        }
        if ((requested == PeriodStatusEnum.FUTURE_ENTERABLE || requested == PeriodStatusEnum.LOCKED)
                && mode != FinanceMode.THICK) {
            throw new EvyoogException("THIN_TRANSITION_NOT_ALLOWED",
                    requested + " status is only available for THICK finance mode Ledgers.");
        }
    }

    private void rejectEventOnly(Ledger ledger) {
        if (ledger.getFinanceMode() == FinanceMode.EVENT_ONLY) {
            throw new EvyoogException("EVENT_ONLY_NO_PERIOD_STATUS",
                    "Event-only Ledgers do not use period status control. " +
                            "Period management for Event-only mode is a Phase 2 feature.");
        }
    }

    private Ledger resolveLedger(UUID legalEntityId) {
        return legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId)
                .orElseThrow(() -> new EvyoogException("NO_PRIMARY_LEDGER",
                        "No primary Ledger found for this Legal Entity."));
    }

    private PeriodStatus findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PeriodStatus", id));
    }
}
