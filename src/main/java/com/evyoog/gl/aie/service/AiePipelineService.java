package com.evyoog.gl.aie.service;

import com.evyoog.gl.aie.domain.BatchAckLog;
import com.evyoog.gl.aie.domain.DeduplicationLog;
import com.evyoog.gl.aie.domain.InterfaceBatch;
import com.evyoog.gl.aie.domain.InterfaceError;
import com.evyoog.gl.aie.domain.InterfaceLine;
import com.evyoog.gl.aie.dto.AieImportRequest;
import com.evyoog.gl.aie.dto.AieImportResponse;
import com.evyoog.gl.aie.dto.AieLineErrorResponse;
import com.evyoog.gl.aie.dto.AieLineRequest;
import com.evyoog.gl.aie.dto.BatchStatusResponse;
import com.evyoog.gl.aie.dto.ResubmitRequest;
import com.evyoog.gl.aie.repository.BatchAckLogRepository;
import com.evyoog.gl.aie.repository.DeduplicationLogRepository;
import com.evyoog.gl.aie.repository.InterfaceBatchRepository;
import com.evyoog.gl.aie.repository.InterfaceErrorRepository;
import com.evyoog.gl.aie.repository.InterfaceLineRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.posting.domain.JournalCategory;
import com.evyoog.gl.posting.domain.JournalHeader;
import com.evyoog.gl.posting.domain.JournalSource;
import com.evyoog.gl.posting.dto.PostingLineRequest;
import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.dto.PostingResult;
import com.evyoog.gl.posting.repository.JournalCategoryRepository;
import com.evyoog.gl.posting.repository.JournalHeaderRepository;
import com.evyoog.gl.posting.repository.JournalSourceRepository;
import com.evyoog.gl.posting.service.PostingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GL-16 AIE REST Journal Import. Runs every inbound batch through a 4-stage
 * pipeline — INGEST, VALIDATE, ENRICH, POST — persisting the outcome of every
 * stage in the same transaction as the request that triggered it. A FAILED
 * batch is a normal, queryable outcome, not a rolled-back one: only the
 * duplicate-event-id check (Stage 1, before any row is written) throws and
 * aborts the transaction.
 *
 * Accounting rules the Posting Engine (GL-15) already enforces — period gate,
 * account/dimension existence, LE authorisation, currency, approval — are not
 * duplicated here. This service owns only what is unique to the import
 * pipeline: idempotency, structural line validation, and account-code to
 * DimensionValue resolution.
 */
@Service
@RequiredArgsConstructor
public class AiePipelineService {

    private static final String IMPORT_CODE = "IMPORT";

    private final InterfaceBatchRepository batchRepository;
    private final InterfaceLineRepository lineRepository;
    private final InterfaceErrorRepository errorRepository;
    private final DeduplicationLogRepository deduplicationLogRepository;
    private final BatchAckLogRepository batchAckLogRepository;
    private final LegalEntityLedgerRepository legalEntityLedgerRepository;
    private final FinanceDimensionRepository financeDimensionRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final JournalSourceRepository journalSourceRepository;
    private final JournalCategoryRepository journalCategoryRepository;
    private final JournalHeaderRepository journalHeaderRepository;
    private final PostingEngine postingEngine;
    private final AuditService auditService;

    @Transactional
    public AieImportResponse ingest(AieImportRequest request) {
        return ingest(request, IMPORT_CODE, IMPORT_CODE);
    }

    // Overload used by callers that need a Journal Source/Category other than
    // the default IMPORT/IMPORT (e.g. Opening Balance Import posts as
    // MANUAL/OPENING) without duplicating the 4-stage pipeline below.
    @Transactional
    public AieImportResponse ingest(AieImportRequest request, String journalSourceCode, String journalCategoryCode) {
        if (deduplicationLogRepository.existsByEventId(request.eventId())) {
            throw new EvyoogException("DUPLICATE_EVENT_ID",
                    "A batch with event_id '" + request.eventId() + "' has already been processed.");
        }

        DeduplicationLog dedup = deduplicationLogRepository.save(DeduplicationLog.builder()
                .eventId(request.eventId())
                .sourceSystem(request.sourceSystem())
                .build());

        InterfaceBatch batch = batchRepository.save(InterfaceBatch.builder()
                .eventId(request.eventId())
                .sourceSystem(request.sourceSystem())
                .importTransport("REST")
                .legalEntityId(request.legalEntityId())
                .ledgerId(request.ledgerId())
                .accountingPeriodId(request.accountingPeriodId())
                .batchReference(request.batchReference())
                .status("PENDING")
                .totalLines(request.lines().size())
                .createdBy(request.createdBy())
                .build());

        dedup.setBatchId(batch.getId());
        deduplicationLogRepository.save(dedup);

        List<InterfaceLine> lines = lineRepository.saveAll(request.lines().stream()
                .map(l -> toInterfaceLine(l, batch))
                .collect(Collectors.toList()));

        return runPipeline(batch, lines, request, journalSourceCode, journalCategoryCode);
    }

    @Transactional
    public AieImportResponse resubmit(UUID batchId, ResubmitRequest request) {
        InterfaceBatch original = findBatch(batchId);
        if (!"FAILED".equals(original.getStatus())) {
            throw new EvyoogException("BATCH_NOT_FAILED",
                    "Only FAILED batches can be resubmitted. Current status: " + original.getStatus());
        }

        List<InterfaceLine> originalLines = lineRepository.findByBatchIdOrderByLineNumberAsc(batchId);

        AieImportRequest replay = new AieImportRequest(
                "RESUBMIT-" + UUID.randomUUID(),
                original.getSourceSystem(),
                original.getLegalEntityId(),
                original.getLedgerId(),
                original.getAccountingPeriodId(),
                original.getBatchReference(),
                "Resubmit of batch " + original.getId(),
                request.resubmittedBy(),
                originalLines.stream().map(this::toLineRequest).toList());

        return ingest(replay);
    }

    @Transactional(readOnly = true)
    public BatchStatusResponse getBatchStatus(UUID batchId) {
        return toStatusResponse(findBatch(batchId));
    }

    @Transactional(readOnly = true)
    public List<AieLineErrorResponse> getErrors(UUID batchId) {
        findBatch(batchId);
        return errorRepository.findByBatchId(batchId).stream()
                .map(e -> AieLineErrorResponse.builder()
                        .lineNumber(e.getLineNumber())
                        .errorCode(e.getErrorCode())
                        .errorMessage(e.getErrorMessage())
                        .errorStage(e.getErrorStage())
                        .fieldName(e.getFieldName())
                        .build())
                .toList();
    }

    // ── pipeline ─────────────────────────────────────────────────────────────

    private AieImportResponse runPipeline(InterfaceBatch batch, List<InterfaceLine> lines, AieImportRequest request,
                                           String journalSourceCode, String journalCategoryCode) {
        batch.setStatus("VALIDATING");
        batchRepository.save(batch);

        List<InterfaceError> structuralErrors = validate(batch, lines);
        if (!structuralErrors.isEmpty()) {
            return fail(batch, structuralErrors);
        }
        batch.setStatus("VALIDATED");
        batchRepository.save(batch);

        Ledger ledger = legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(request.legalEntityId())
                .orElse(null);
        if (ledger == null) {
            return fail(batch, List.of(buildError(batch, null, "NO_PRIMARY_LEDGER",
                    "No primary Ledger found for this Legal Entity.", "ENRICH", null)));
        }

        List<InterfaceError> enrichErrors = enrich(batch, lines, ledger.getId());
        if (!enrichErrors.isEmpty()) {
            return fail(batch, enrichErrors);
        }
        batch.setStatus("ENRICHED");
        batchRepository.save(batch);

        batch.setStatus("POSTING");
        batchRepository.save(batch);

        JournalSource source;
        JournalCategory category;
        try {
            source = journalSourceRepository.findByCode(journalSourceCode)
                    .orElseThrow(() -> new EvyoogException("IMPORT_SOURCE_NOT_CONFIGURED",
                            "No '" + journalSourceCode + "' Journal Source configured."));
            category = journalCategoryRepository.findByCode(journalCategoryCode)
                    .orElseThrow(() -> new EvyoogException("IMPORT_CATEGORY_NOT_CONFIGURED",
                            "No '" + journalCategoryCode + "' Journal Category configured."));
        } catch (EvyoogException ex) {
            return fail(batch, List.of(buildError(batch, null, ex.getCode(), ex.getMessage(), "POST", null)));
        }

        PostingRequest postingRequest = buildPostingRequest(request, lines, source, category);

        PostingResult result;
        try {
            result = postingEngine.post(postingRequest);
        } catch (EvyoogException ex) {
            AieImportResponse response = fail(batch,
                    List.of(buildError(batch, null, ex.getCode(), ex.getMessage(), "POST", null)));
            writeAck(batch, null, "GL_FAILED", "FAILED");
            return response;
        }

        JournalHeader journal = journalHeaderRepository.findById(result.getJournalHeaderId())
                .orElseThrow(() -> new ResourceNotFoundException("JournalHeader", result.getJournalHeaderId()));

        lines.forEach(line -> line.setLineStatus("POSTED"));
        lineRepository.saveAll(lines);

        batch.setStatus("POSTED");
        batch.setValidLines(lines.size());
        batch.setErrorLines(0);
        batch.setJournalHeaderId(journal.getId());
        InterfaceBatch savedBatch = batchRepository.save(batch);
        auditService.log(AuditAction.UPDATE, "interface_batch", savedBatch.getId(), null,
                batchAuditSnapshot(savedBatch), request.createdBy());

        writeAck(savedBatch, journal.getId(), "GL_POSTED", "ACKNOWLEDGED");

        return AieImportResponse.builder()
                .batchId(savedBatch.getId())
                .batchReference(savedBatch.getBatchReference())
                .eventId(savedBatch.getEventId())
                .status("POSTED")
                .totalLines(savedBatch.getTotalLines())
                .validLines(lines.size())
                .errorLines(0)
                .journalHeaderId(journal.getId())
                .journalNumber(journal.getJournalNumber())
                .message("Batch imported and posted successfully.")
                .errors(List.of())
                .build();
    }

    // ── Rule 1 — balance check. Rule 2 — exactly one of DR/CR per line. ──────
    private List<InterfaceError> validate(InterfaceBatch batch, List<InterfaceLine> lines) {
        List<InterfaceError> errors = new ArrayList<>();

        BigDecimal totalDr = sum(lines, InterfaceLine::getDebitAmount);
        BigDecimal totalCr = sum(lines, InterfaceLine::getCreditAmount);
        if (totalDr.compareTo(totalCr) != 0) {
            errors.add(buildError(batch, null, "UNBALANCED",
                    "Batch does not balance. DR: " + totalDr + " CR: " + totalCr, "VALIDATE", null));
        }

        for (InterfaceLine line : lines) {
            boolean hasDr = line.getDebitAmount() != null && line.getDebitAmount().compareTo(BigDecimal.ZERO) > 0;
            boolean hasCr = line.getCreditAmount() != null && line.getCreditAmount().compareTo(BigDecimal.ZERO) > 0;
            if (hasDr == hasCr) {
                errors.add(buildError(batch, line.getLineNumber(), "INVALID_LINE_AMOUNT",
                        "Each line must have either debitAmount or creditAmount, not both or neither.",
                        "VALIDATE", "debitAmount/creditAmount"));
            }
        }

        return errors;
    }

    // Resolves accountCode against the Ledger's Natural Account dimension and
    // fills GST/TDS flags from the account master when the caller left them unset.
    private List<InterfaceError> enrich(InterfaceBatch batch, List<InterfaceLine> lines, UUID ledgerId) {
        List<InterfaceError> errors = new ArrayList<>();

        FinanceDimension naturalAcctDim = financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT)
                .orElse(null);
        if (naturalAcctDim == null) {
            errors.add(buildError(batch, null, "NO_NATURAL_ACCOUNT_DIM",
                    "No Natural Account dimension found for this Ledger.", "ENRICH", null));
            return errors;
        }

        for (InterfaceLine line : lines) {
            DimensionValue account = dimensionValueRepository
                    .findByFinanceDimensionIdAndCodeAndIsActiveTrue(naturalAcctDim.getId(), line.getAccountCode())
                    .orElse(null);
            if (account == null) {
                errors.add(buildError(batch, line.getLineNumber(), "ACCOUNT_CODE_NOT_FOUND",
                        "No account found for code: " + line.getAccountCode(), "ENRICH", "accountCode"));
                continue;
            }
            line.setNaturalAccountValueId(account.getId());
            if (!line.getGstApplicable()) {
                line.setGstApplicable(account.isGstApplicable());
            }
            if (!line.getTdsApplicable()) {
                line.setTdsApplicable(account.isTdsApplicable());
                if (line.getTdsSection() == null) {
                    line.setTdsSection(account.getTdsSection());
                }
            }
            line.setLineStatus("ENRICHED");
        }

        if (errors.isEmpty()) {
            lineRepository.saveAll(lines);
        }
        return errors;
    }

    private PostingRequest buildPostingRequest(AieImportRequest request, List<InterfaceLine> lines,
                                                JournalSource source, JournalCategory category) {
        List<PostingLineRequest> postingLines = lines.stream()
                .map(line -> PostingLineRequest.builder()
                        .accountCombination(line.getAccountCombination() != null ? line.getAccountCombination() : Map.of())
                        .naturalAccountValueId(line.getNaturalAccountValueId())
                        .debitAmount(line.getDebitAmount())
                        .creditAmount(line.getCreditAmount())
                        .description(line.getDescription())
                        .gstApplicable(line.getGstApplicable())
                        .gstType(line.getGstType())
                        .tdsApplicable(line.getTdsApplicable())
                        .tdsSection(line.getTdsSection())
                        .build())
                .toList();

        LocalDate today = LocalDate.now();

        return PostingRequest.builder()
                .legalEntityId(request.legalEntityId())
                .accountingPeriodId(request.accountingPeriodId())
                .journalSourceId(source.getId())
                .journalCategoryId(category.getId())
                .description(request.description())
                .glDate(today)
                .accountingDate(today)
                .lines(postingLines)
                .performedBy(request.createdBy())
                .externalReference(request.batchReference())
                .build();
    }

    private AieImportResponse fail(InterfaceBatch batch, List<InterfaceError> errors) {
        errorRepository.saveAll(errors);
        batch.setErrorLines(errors.size());
        batch.setValidLines(0);
        batch.setStatus("FAILED");
        batch.setErrorSummary(errors.size() + " validation error(s)");
        InterfaceBatch saved = batchRepository.save(batch);
        auditService.log(AuditAction.UPDATE, "interface_batch", saved.getId(), null,
                batchAuditSnapshot(saved), saved.getCreatedBy());

        return AieImportResponse.builder()
                .batchId(saved.getId())
                .batchReference(saved.getBatchReference())
                .eventId(saved.getEventId())
                .status("FAILED")
                .totalLines(saved.getTotalLines())
                .validLines(0)
                .errorLines(errors.size())
                .message(saved.getErrorSummary())
                .errors(errors.stream()
                        .map(e -> AieLineErrorResponse.builder()
                                .lineNumber(e.getLineNumber())
                                .errorCode(e.getErrorCode())
                                .errorMessage(e.getErrorMessage())
                                .errorStage(e.getErrorStage())
                                .fieldName(e.getFieldName())
                                .build())
                        .toList())
                .build();
    }

    private void writeAck(InterfaceBatch batch, UUID journalHeaderId, String eventType, String status) {
        BatchAckLog ack = batchAckLogRepository.save(BatchAckLog.builder()
                .batchId(batch.getId())
                .journalHeaderId(journalHeaderId)
                .eventType(eventType)
                .status(status)
                .build());
        auditService.log(AuditAction.CREATE, "batch_ack_log", ack.getId(), null, ack, batch.getCreatedBy());
    }

    private InterfaceError buildError(InterfaceBatch batch, Integer lineNumber, String code, String message,
                                       String stage, String field) {
        return InterfaceError.builder()
                .batch(batch)
                .lineNumber(lineNumber)
                .errorCode(code)
                .errorMessage(message)
                .errorStage(stage)
                .fieldName(field)
                .build();
    }

    private InterfaceLine toInterfaceLine(AieLineRequest lineRequest, InterfaceBatch batch) {
        return InterfaceLine.builder()
                .batch(batch)
                .lineNumber(lineRequest.lineNumber())
                .accountCode(lineRequest.accountCode())
                .accountCombination(lineRequest.accountCombination())
                .debitAmount(lineRequest.debitAmount())
                .creditAmount(lineRequest.creditAmount())
                .description(lineRequest.description())
                .gstType(lineRequest.gstType())
                .gstApplicable(Boolean.TRUE.equals(lineRequest.gstApplicable()))
                .tdsSection(lineRequest.tdsSection())
                .tdsApplicable(Boolean.TRUE.equals(lineRequest.tdsApplicable()))
                .lineStatus("PENDING")
                .build();
    }

    private AieLineRequest toLineRequest(InterfaceLine line) {
        return new AieLineRequest(
                line.getLineNumber(),
                line.getAccountCode(),
                line.getAccountCombination(),
                line.getDebitAmount(),
                line.getCreditAmount(),
                line.getDescription(),
                line.getGstType(),
                line.getGstApplicable(),
                line.getTdsSection(),
                line.getTdsApplicable());
    }

    private BigDecimal sum(List<InterfaceLine> lines, Function<InterfaceLine, BigDecimal> extractor) {
        return lines.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BatchStatusResponse toStatusResponse(InterfaceBatch batch) {
        return BatchStatusResponse.builder()
                .batchId(batch.getId())
                .batchReference(batch.getBatchReference())
                .eventId(batch.getEventId())
                .sourceSystem(batch.getSourceSystem())
                .status(batch.getStatus())
                .totalLines(batch.getTotalLines())
                .validLines(batch.getValidLines())
                .errorLines(batch.getErrorLines())
                .errorSummary(batch.getErrorSummary())
                .journalHeaderId(batch.getJournalHeaderId())
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .build();
    }

    private Map<String, Object> batchAuditSnapshot(InterfaceBatch batch) {
        return Map.of(
                "status", batch.getStatus(),
                "totalLines", batch.getTotalLines(),
                "validLines", batch.getValidLines(),
                "errorLines", batch.getErrorLines());
    }

    private InterfaceBatch findBatch(UUID id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new EvyoogException("BATCH_NOT_FOUND", "Batch not found: " + id, HttpStatus.NOT_FOUND));
    }
}
