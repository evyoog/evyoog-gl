package com.evyoog.gl.posting.service;

import com.evyoog.gl.aie.domain.SlaEventLog;
import com.evyoog.gl.aie.repository.SlaEventLogRepository;
import com.evyoog.gl.combination.service.AccountCombinationService;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
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
import com.evyoog.gl.periodstatus.service.PeriodStatusService;
import com.evyoog.gl.posting.domain.AccountBalance;
import com.evyoog.gl.posting.domain.JournalCategory;
import com.evyoog.gl.posting.domain.JournalHeader;
import com.evyoog.gl.posting.domain.JournalLine;
import com.evyoog.gl.posting.domain.JournalSource;
import com.evyoog.gl.posting.domain.JournalStatus;
import com.evyoog.gl.posting.dto.PostingLineRequest;
import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.dto.PostingResult;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.posting.repository.JournalCategoryRepository;
import com.evyoog.gl.posting.repository.JournalHeaderRepository;
import com.evyoog.gl.posting.repository.JournalSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The accounting core of eVyoog. Every journal that is ever posted passes
 * through {@link #post(PostingRequest)}. Called internally by GL-11 (Manual
 * Journal Entry) and GL-16 (AIE REST Import) — this capability has no REST
 * controller of its own.
 */
@Service
@RequiredArgsConstructor
public class PostingEngine {

    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final JournalSourceRepository journalSourceRepository;
    private final JournalCategoryRepository journalCategoryRepository;
    private final JournalHeaderRepository journalHeaderRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final FinanceDimensionRepository financeDimensionRepository;
    private final SlaEventLogRepository slaEventLogRepository;
    private final PeriodStatusService periodStatusService;
    private final AccountCombinationService accountCombinationService;
    private final AuditService auditService;

    @Transactional
    public PostingResult post(PostingRequest request) {
        // Read financeMode ONCE — branch — never check again.
        Ledger ledger = legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(request.getLegalEntityId())
                .orElseThrow(() -> new EvyoogException("NO_PRIMARY_LEDGER",
                        "No primary Ledger found for this Legal Entity."));

        FinanceMode mode = ledger.getFinanceMode();

        return switch (mode) {
            case THICK -> postThick(request, ledger);
            case THIN -> postThin(request, ledger);
            case EVENT_ONLY -> emitEvent(request, ledger);
        };
    }

    private PostingResult postThick(PostingRequest request, Ledger ledger) {
        validateBalance(request.getLines());
        periodStatusService.validatePeriodOpen(request.getLegalEntityId(), request.getAccountingPeriodId());
        validateAccountsExist(request.getLines(), ledger.getId());
        validateAccountsPostable(request.getLines());
        enrichWithDefaults(request.getLines(), ledger.getId());
        validateDimensionValues(request.getLines(), ledger.getId());
        validateBalancingSegments(request.getLines(), ledger);
        validateAccountCombinations(request.getLines(), ledger, request.getLegalEntityId(), request.getPerformedBy());
        validateLegalEntityAuthorised(request.getLegalEntityId(), ledger.getId());
        validateCurrency(request, ledger);
        JournalSource source = resolveJournalSource(request.getJournalSourceId());
        validateApproval(request, source);

        return createAndPostJournal(request, ledger, source, FinanceMode.THICK);
    }

    private PostingResult postThin(PostingRequest request, Ledger ledger) {
        validateBalance(request.getLines());
        periodStatusService.validatePeriodOpen(request.getLegalEntityId(), request.getAccountingPeriodId());
        validateAccountsExist(request.getLines(), ledger.getId());
        validateAccountsPostable(request.getLines());
        enrichWithDefaults(request.getLines(), ledger.getId());
        validateDimensionValues(request.getLines(), ledger.getId());
        validateBalancingSegments(request.getLines(), ledger);
        validateAccountCombinations(request.getLines(), ledger, request.getLegalEntityId(), request.getPerformedBy());
        validateLegalEntityAuthorised(request.getLegalEntityId(), ledger.getId());
        // THIN skips rule 7 (currency) and rule 8 (approval).
        JournalSource source = resolveJournalSource(request.getJournalSourceId());

        return createAndPostJournal(request, ledger, source, FinanceMode.THIN);
    }

    private PostingResult emitEvent(PostingRequest request, Ledger ledger) {
        // No journal created, no balance updated — validate event shape only.
        if (request.getAccountingPeriodId() == null) {
            throw new EvyoogException("PERIOD_REQUIRED_FOR_EVENT",
                    "An accounting period ID is required for Event-only mode to tag " +
                            "the SLA event for operational reporting.",
                    HttpStatus.BAD_REQUEST);
        }

        SlaEventLog event = SlaEventLog.builder()
                .ledgerId(ledger.getId())
                .legalEntityId(request.getLegalEntityId())
                .accountingPeriodId(request.getAccountingPeriodId())
                .eventPayload(request.getEventPayload())
                .status("EMITTED")
                .build();
        SlaEventLog saved = slaEventLogRepository.save(event);

        auditService.log(AuditAction.CREATE, "sla_event_log", saved.getId(), null, saved, request.getPerformedBy());

        return PostingResult.eventEmitted(saved.getId());
    }

    private PostingResult createAndPostJournal(PostingRequest request, Ledger ledger,
                                                JournalSource source, FinanceMode mode) {
        LegalEntity legalEntity = legalEntityRepository.findById(request.getLegalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("LegalEntity", request.getLegalEntityId()));
        AccountingPeriod period = accountingPeriodRepository.findById(request.getAccountingPeriodId())
                .orElseThrow(() -> new ResourceNotFoundException("AccountingPeriod", request.getAccountingPeriodId()));
        JournalCategory category = resolveJournalCategory(request.getJournalCategoryId());

        JournalHeader header = JournalHeader.builder()
                .journalNumber(generateJournalNumber())
                .legalEntity(legalEntity)
                .ledger(ledger)
                .accountingPeriod(period)
                .journalSource(source)
                .journalCategory(category)
                .description(request.getDescription())
                .glDate(request.getGlDate())
                .accountingDate(request.getAccountingDate())
                .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "INR")
                .exchangeRate(request.getExchangeRate() != null ? request.getExchangeRate() : BigDecimal.ONE)
                .totalDebit(sum(request.getLines(), PostingLineRequest::getDebitAmount))
                .totalCredit(sum(request.getLines(), PostingLineRequest::getCreditAmount))
                .status(JournalStatus.POSTED)
                // IMMUTABLE — set once here, never updated after this point.
                .financeModeSnapshot(mode)
                .postedAt(Instant.now())
                .postedBy(request.getPerformedBy())
                .extendedAttributes(request.getExtendedAttributes())
                .createdBy(request.getPerformedBy())
                .updatedBy(request.getPerformedBy())
                .build();

        List<JournalLine> lines = buildJournalLines(request.getLines(), header);
        header.setLines(lines);

        JournalHeader saved = journalHeaderRepository.save(header);
        updateAccountBalances(lines, saved, request.getPerformedBy());

        auditService.log(AuditAction.CREATE, "journal_header", saved.getId(), null,
                journalAuditSnapshot(saved), request.getPerformedBy());

        return PostingResult.posted(saved.getId(), saved.getJournalNumber(), mode);
    }

    private List<JournalLine> buildJournalLines(List<PostingLineRequest> lineRequests, JournalHeader header) {
        List<JournalLine> lines = new ArrayList<>();
        int lineNumber = 1;
        for (PostingLineRequest lineRequest : lineRequests) {
            DimensionValue naturalAccount = dimensionValueRepository.findById(lineRequest.getNaturalAccountValueId())
                    .orElseThrow(() -> new EvyoogException("ACCOUNT_NOT_FOUND",
                            "Account not found: " + lineRequest.getNaturalAccountValueId(), HttpStatus.NOT_FOUND));
            lines.add(JournalLine.builder()
                    .journalHeader(header)
                    .lineNumber(lineNumber++)
                    .accountCombination(lineRequest.getAccountCombination() != null
                            ? lineRequest.getAccountCombination() : Map.of())
                    .naturalAccount(naturalAccount)
                    .debitAmount(lineRequest.getDebitAmount())
                    .creditAmount(lineRequest.getCreditAmount())
                    .currencyCode(lineRequest.getCurrencyCode() != null ? lineRequest.getCurrencyCode() : "INR")
                    .description(lineRequest.getDescription())
                    .gstApplicable(Boolean.TRUE.equals(lineRequest.getGstApplicable()))
                    .gstType(lineRequest.getGstType())
                    .tdsApplicable(Boolean.TRUE.equals(lineRequest.getTdsApplicable()))
                    .tdsSection(lineRequest.getTdsSection())
                    .extendedAttributes(lineRequest.getExtendedAttributes())
                    .build());
        }
        return lines;
    }

    // ── Rule 1 — balance check. Always fires first. ─────────────────────────
    private void validateBalance(List<PostingLineRequest> lines) {
        if (lines == null || lines.size() < 2) {
            throw new EvyoogException("MINIMUM_TWO_LINES",
                    "A journal entry must have at least two lines.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        BigDecimal totalDr = sum(lines, PostingLineRequest::getDebitAmount);
        BigDecimal totalCr = sum(lines, PostingLineRequest::getCreditAmount);
        if (totalDr.compareTo(totalCr) != 0) {
            throw new EvyoogException("JOURNAL_NOT_BALANCED",
                    "Journal is not balanced. Total debits: " + totalDr + ", Total credits: " + totalCr +
                            ". Debits must equal credits.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── Rule 3 — accounts exist in the Ledger's Chart of Accounts. ──────────
    private void validateAccountsExist(List<PostingLineRequest> lines, UUID ledgerId) {
        FinanceDimension naturalAcctDim = financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT)
                .orElseThrow(() -> new EvyoogException("NO_NATURAL_ACCOUNT_DIM",
                        "No Natural Account dimension found for this Ledger."));

        for (PostingLineRequest line : lines) {
            dimensionValueRepository
                    .findByIdAndFinanceDimensionIdAndIsActiveTrue(line.getNaturalAccountValueId(), naturalAcctDim.getId())
                    .orElseThrow(() -> new EvyoogException("ACCOUNT_NOT_FOUND",
                            "Account not found in Chart of Accounts for this Ledger: "
                                    + line.getNaturalAccountValueId(), HttpStatus.NOT_FOUND));
        }
    }

    // ── Rule 4 — accounts are postable (leaf, not summary). ─────────────────
    private void validateAccountsPostable(List<PostingLineRequest> lines) {
        for (PostingLineRequest line : lines) {
            DimensionValue account = dimensionValueRepository.findById(line.getNaturalAccountValueId())
                    .orElseThrow(() -> new ResourceNotFoundException("DimensionValue", line.getNaturalAccountValueId()));
            if (account.isSummary()) {
                throw new EvyoogException("SUMMARY_ACCOUNT_NOT_POSTABLE",
                        "Account " + account.getCode() + " - " + account.getName() +
                                " is a summary account and cannot be posted to. Use a postable (leaf) account.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (!account.isPostable()) {
                throw new EvyoogException("ACCOUNT_NOT_POSTABLE",
                        "Account " + account.getCode() + " is not marked as postable.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }
    }

    // ── Rule 4b — auto-insert default values for optional dimensions that ───
    // ── the caller left out of the account combination, before Rule 5 ───────
    // ── validation. NATURAL_ACCOUNT (carried via naturalAccountValueId) and ─
    // ── required dimensions (must be explicit) are never auto-filled. ───────
    private void enrichWithDefaults(List<PostingLineRequest> lines, UUID ledgerId) {
        List<FinanceDimension> optionalDimensions = financeDimensionRepository
                .findByLedgerIdAndIsActiveTrue(ledgerId).stream()
                .filter(dimension -> !dimension.isRequired())
                .filter(dimension -> dimension.getDimensionType() != DimensionType.NATURAL_ACCOUNT)
                .toList();

        if (optionalDimensions.isEmpty()) {
            return;
        }

        for (PostingLineRequest line : lines) {
            Map<String, String> combination = line.getAccountCombination() != null
                    ? new HashMap<>(line.getAccountCombination()) : new HashMap<>();
            boolean changed = false;
            for (FinanceDimension dimension : optionalDimensions) {
                String key = dimension.getDimensionType().name();
                if (combination.containsKey(key)) {
                    continue;
                }
                DimensionValue defaultValue = dimensionValueRepository
                        .findByFinanceDimensionIdAndIsDefaultTrue(dimension.getId())
                        .orElse(null);
                if (defaultValue != null) {
                    combination.put(key, defaultValue.getCode());
                    changed = true;
                }
            }
            if (changed) {
                line.setAccountCombination(combination);
            }
        }
    }

    // ── Rule 5 — every non-natural-account dimension value in the account ──
    // ── combination must exist and be active for this Ledger, and every ────
    // ── required dimension (other than NATURAL_ACCOUNT, which is carried ───
    // ── via naturalAccountValueId instead of the combination map) must be ──
    // ── present on every line. ───────────────────────────────────────────
    private void validateDimensionValues(List<PostingLineRequest> lines, UUID ledgerId) {
        List<FinanceDimension> requiredDimensions = financeDimensionRepository
                .findByLedgerIdAndIsActiveTrue(ledgerId).stream()
                .filter(FinanceDimension::isRequired)
                .filter(dimension -> dimension.getDimensionType() != DimensionType.NATURAL_ACCOUNT)
                .toList();

        for (PostingLineRequest line : lines) {
            Map<String, String> combination = line.getAccountCombination() != null
                    ? line.getAccountCombination() : Map.of();

            for (Map.Entry<String, String> entry : combination.entrySet()) {
                DimensionType type;
                try {
                    type = DimensionType.valueOf(entry.getKey());
                } catch (IllegalArgumentException ex) {
                    throw new EvyoogException("INVALID_DIMENSION_TYPE",
                            "Unknown dimension type in account combination: " + entry.getKey(),
                            HttpStatus.BAD_REQUEST);
                }
                if (type == DimensionType.NATURAL_ACCOUNT) {
                    continue;
                }
                FinanceDimension dimension = financeDimensionRepository
                        .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, type)
                        .orElseThrow(() -> new EvyoogException("DIMENSION_NOT_CONFIGURED",
                                "Ledger has no active Finance Dimension of type " + type));
                dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(dimension.getId(), entry.getValue())
                        .orElseThrow(() -> new EvyoogException("DIMENSION_VALUE_NOT_FOUND",
                                "Dimension value not found for " + entry.getKey() + " = " + entry.getValue(),
                                HttpStatus.NOT_FOUND));
            }

            for (FinanceDimension required : requiredDimensions) {
                if (!combination.containsKey(required.getDimensionType().name())) {
                    throw new EvyoogException("MISSING_REQUIRED_DIMENSION",
                            "Required dimension '" + required.getDimensionType() + "' (" + required.getName() +
                                    ") is missing from account combination.", HttpStatus.BAD_REQUEST);
                }
            }
        }
    }

    // ── Rule 11 — balancing segment crossing check. ──────────────────────────
    // If any Finance Dimension on the Ledger's COA Structure has
    // is_balancing=TRUE, every journal line must carry the SAME value for
    // that dimension — a differing value, or a line missing it entirely,
    // means the journal crosses the balancing segment and is rejected.
    // Legal Entity is the implicit PRIMARY balancing segment (enforced via
    // legalEntityId scoping elsewhere) — this rule only enforces the
    // customer-configured SECONDARY/TERTIARY balancing segments. Runs after
    // Rule 5 (dimension values validated + defaults enriched) so a missing
    // value here is a genuine crossing, not an unfilled default.
    private void validateBalancingSegments(List<PostingLineRequest> lines, Ledger ledger) {
        if (lines == null || lines.isEmpty() || ledger.getCoaStructure() == null) {
            return;
        }

        List<FinanceDimension> balancingDimensions = financeDimensionRepository
                .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(ledger.getCoaStructure().getId());

        for (FinanceDimension dimension : balancingDimensions) {
            String dimensionKey = dimension.getDimensionType().name();

            Set<String> valuesFound = lines.stream()
                    .map(line -> line.getAccountCombination() != null
                            ? line.getAccountCombination().get(dimensionKey) : null)
                    .collect(Collectors.toSet());

            if (valuesFound.contains(null) || valuesFound.size() > 1) {
                String values = valuesFound.stream()
                        .map(value -> value == null ? "[missing]" : value)
                        .collect(Collectors.joining(", "));
                throw new EvyoogException("BALANCING_SEGMENT_CROSSED",
                        String.format(
                                "Journal crosses the '%s' balancing segment (%s). " +
                                        "All lines must have the same value for '%s'. " +
                                        "Values found: [%s]. " +
                                        "To post across %s values, enable intercompany accounting (Phase 3).",
                                dimension.getName(), dimensionKey, dimension.getName(),
                                values, dimension.getName()),
                        HttpStatus.BAD_REQUEST);
            }
        }
    }

    // ── Rule 10 — the account combination must be registered (or, when the ──
    // ── Ledger allows Dynamic Insert, is auto-registered on first use). ─────
    // ── Lines with no account combination (natural-account-only postings) ───
    // ── are exempt — there is nothing to register. ───────────────────────────
    private void validateAccountCombinations(List<PostingLineRequest> lines, Ledger ledger, UUID legalEntityId,
                                              String performedBy) {
        boolean allowDynamicInsert = ledger.isAllowDynamicInsert();
        for (PostingLineRequest line : lines) {
            Map<String, String> combination = line.getAccountCombination();
            if (combination == null || combination.isEmpty()) {
                continue;
            }
            accountCombinationService.validate(ledger.getId(), legalEntityId, combination, allowDynamicInsert, performedBy);
        }
    }

    // ── Rule 6 — the Legal Entity must be authorised to post to this Ledger.
    private void validateLegalEntityAuthorised(UUID legalEntityId, UUID ledgerId) {
        if (!legalEntityLedgerRepository.existsByLegalEntityIdAndLedgerIdAndIsActiveTrue(legalEntityId, ledgerId)) {
            throw new EvyoogException("LEGAL_ENTITY_NOT_AUTHORISED",
                    "This Legal Entity is not authorised to post to this Ledger.");
        }
    }

    // ── Rule 7 — currency valid. THICK mode only. ────────────────────────────
    private void validateCurrency(PostingRequest request, Ledger ledger) {
        String currency = request.getCurrencyCode() != null ? request.getCurrencyCode() : "INR";
        boolean crossCurrency = !currency.equals(ledger.getFunctionalCurrency());
        boolean hasExchangeRate = request.getExchangeRate() != null
                && request.getExchangeRate().compareTo(BigDecimal.ZERO) > 0;
        if (crossCurrency && !hasExchangeRate) {
            throw new EvyoogException("CURRENCY_EXCHANGE_RATE_REQUIRED",
                    "A positive exchange rate is required when posting in a currency other than " +
                            "the Ledger's functional currency (" + ledger.getFunctionalCurrency() + ").",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── Rule 8 — approval. THICK mode only; THIN skips this rule entirely. ─
    private void validateApproval(PostingRequest request, JournalSource source) {
        if (Boolean.TRUE.equals(source.getRequiresApproval()) && request.getInitialStatus() != JournalStatus.APPROVED) {
            throw new EvyoogException("APPROVAL_REQUIRED",
                    "Journal source " + source.getCode() + " requires approval before posting. " +
                            "Submit for approval first.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // ── account_balance update — atomic, in the same transaction as posting.
    private void updateAccountBalances(List<JournalLine> lines, JournalHeader header, String performedBy) {
        for (JournalLine line : lines) {
            Map<String, String> combination = line.getAccountCombination() == null ? Map.of() : line.getAccountCombination();
            AccountBalance existing = accountBalanceRepository
                    .findByLedgerIdAndLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(
                            header.getLedger().getId(), header.getLegalEntity().getId(),
                            header.getAccountingPeriod().getId(), line.getNaturalAccount().getId())
                    .stream()
                    .filter(ab -> combination.equals(ab.getAccountCombination() == null ? Map.of() : ab.getAccountCombination()))
                    .findFirst()
                    .orElse(null);

            boolean isNew = existing == null;
            AccountBalance balance = isNew
                    ? AccountBalance.builder()
                        .ledger(header.getLedger())
                        .legalEntity(header.getLegalEntity())
                        .accountingPeriod(header.getAccountingPeriod())
                        .naturalAccount(line.getNaturalAccount())
                        .accountCombination(line.getAccountCombination())
                        .build()
                    : existing;
            Map<String, Object> before = isNew ? null : balanceAuditSnapshot(balance);

            if (line.getDebitAmount() != null) {
                balance.setPeriodToDateDr(balance.getPeriodToDateDr().add(line.getDebitAmount()));
                balance.setYearToDateDr(balance.getYearToDateDr().add(line.getDebitAmount()));
            } else {
                balance.setPeriodToDateCr(balance.getPeriodToDateCr().add(line.getCreditAmount()));
                balance.setYearToDateCr(balance.getYearToDateCr().add(line.getCreditAmount()));
            }

            // @Version on AccountBalance handles optimistic locking automatically.
            AccountBalance saved = accountBalanceRepository.save(balance);
            auditService.log(isNew ? AuditAction.CREATE : AuditAction.UPDATE, "account_balance", saved.getId(),
                    before, balanceAuditSnapshot(saved), performedBy);
        }
    }

    private Map<String, Object> balanceAuditSnapshot(AccountBalance balance) {
        return Map.of(
                "periodToDateDr", balance.getPeriodToDateDr(),
                "periodToDateCr", balance.getPeriodToDateCr(),
                "yearToDateDr", balance.getYearToDateDr(),
                "yearToDateCr", balance.getYearToDateCr(),
                "version", balance.getVersion());
    }

    private Map<String, Object> journalAuditSnapshot(JournalHeader header) {
        return Map.of(
                "journalNumber", header.getJournalNumber(),
                "status", header.getStatus(),
                "financeModeSnapshot", header.getFinanceModeSnapshot(),
                "totalDebit", header.getTotalDebit(),
                "totalCredit", header.getTotalCredit());
    }

    // Format: JE-YYWW-NNNNN — YY = 2-digit year, WW = ISO week, NNNNN = sequence.
    private String generateJournalNumber() {
        LocalDate today = LocalDate.now();
        String year = String.valueOf(today.getYear()).substring(2);
        String week = String.format("%02d", today.get(WeekFields.ISO.weekOfWeekBasedYear()));
        Instant weekStart = today.with(WeekFields.ISO.dayOfWeek(), 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long sequence = journalHeaderRepository.countByCreatedAtAfter(weekStart);
        return String.format("JE-%s%s-%05d", year, week, sequence + 1);
    }

    private JournalSource resolveJournalSource(UUID id) {
        return journalSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JournalSource", id));
    }

    private JournalCategory resolveJournalCategory(UUID id) {
        return journalCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("JournalCategory", id));
    }

    private BigDecimal sum(List<PostingLineRequest> lines, Function<PostingLineRequest, BigDecimal> extractor) {
        return lines.stream()
                .map(extractor)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
