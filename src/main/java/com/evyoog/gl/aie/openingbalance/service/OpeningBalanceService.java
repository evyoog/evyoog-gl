package com.evyoog.gl.aie.openingbalance.service;

import com.evyoog.gl.aie.dto.AieImportRequest;
import com.evyoog.gl.aie.dto.AieImportResponse;
import com.evyoog.gl.aie.dto.AieLineErrorResponse;
import com.evyoog.gl.aie.dto.AieLineRequest;
import com.evyoog.gl.aie.openingbalance.dto.OpeningBalanceImportResponse;
import com.evyoog.gl.aie.openingbalance.dto.OpeningBalanceJournalResult;
import com.evyoog.gl.aie.openingbalance.dto.OpeningBalancePreviewLine;
import com.evyoog.gl.aie.openingbalance.dto.OpeningBalancePreviewResponse;
import com.evyoog.gl.aie.service.AiePipelineService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.domain.NormalBalance;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opening Balance Import — reuses the GL-16/GL-17 AIE pipeline for posting so
 * dedup, structural validation and the Posting Engine's own rules are not
 * duplicated. This service owns only what's unique to the Opening Balance
 * flow: the simplified (no DR/CR column) template, and DR/CR auto-classification
 * from each account's {@code normalBalance} before the journal is built.
 *
 * Posts as journalSourceCode=MANUAL / journalCategoryCode=OPENING (both
 * already seeded from V9) via {@link AiePipelineService}'s
 * source/category-aware overload — distinct from the default IMPORT/IMPORT
 * every other AIE caller uses, so Opening Balance journals are identifiable
 * in the Journal Category the same way {@code sourceSystem=OPENING_BALANCE}
 * identifies the batch.
 */
@Service
@RequiredArgsConstructor
public class OpeningBalanceService {

    private static final String SHEET_NAME = "Opening Balances";
    private static final String SOURCE_SYSTEM = "OPENING_BALANCE";
    private static final String JOURNAL_SOURCE_CODE = "MANUAL";
    private static final String JOURNAL_CATEGORY_CODE = "OPENING";

    private final LedgerRepository ledgerRepository;
    private final FinanceDimensionRepository financeDimensionRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final AiePipelineService aiePipelineService;

    public byte[] generateTemplate(UUID ledgerId) throws IOException {
        List<FinanceDimension> dimensions = resolveNonNaturalDimensions(ledgerId);

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            Row header = sheet.createRow(0);

            List<String> headers = new ArrayList<>();
            headers.add("accountCode");
            dimensions.forEach(d -> headers.add(d.getCode()));
            headers.add("balance");
            headers.add("description");
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }

            Row example = sheet.createRow(1);
            example.createCell(0).setCellValue("1100");
            int col = 1;
            for (FinanceDimension dimension : dimensions) {
                example.createCell(col++).setCellValue(exampleValueFor(dimension.getDimensionType()));
            }
            example.createCell(col++).setCellValue(500000);
            example.createCell(col).setCellValue("Cash opening balance");

            workbook.write(out);
            return out.toByteArray();
        }
    }

    public OpeningBalancePreviewResponse preview(MultipartFile file, UUID legalEntityId, UUID ledgerId,
                                                  UUID accountingPeriodId) throws IOException {
        // legalEntityId/accountingPeriodId are only needed to build the journal
        // at import time — the period-open gate and LE checks live solely in
        // the Posting Engine, not duplicated here for a dry-run preview.
        return buildPreview(file, ledgerId).response();
    }

    public OpeningBalanceImportResponse importBalances(MultipartFile file, UUID legalEntityId, UUID ledgerId,
                                                         UUID accountingPeriodId, String createdBy) throws IOException {
        ParsedPreview parsed = buildPreview(file, ledgerId);
        OpeningBalancePreviewResponse preview = parsed.response();

        if (preview.errorLines() > 0) {
            return OpeningBalanceImportResponse.builder()
                    .success(false)
                    .totalLines(preview.totalLines())
                    .postedLines(0)
                    .journalCount(0)
                    .journals(List.of())
                    .message(preview.errorLines() + " line(s) failed validation. No journal was posted.")
                    .errors(preview.errors())
                    .build();
        }

        // A Ledger whose COA Structure has a 2nd balancing segment configured
        // (isBalancing=true, balancingSequence=2 — e.g. Business Unit/Company)
        // must never post all Opening Balance lines as one journal: any two
        // lines carrying different segment values would fail PostingEngine
        // Rule 11 (BALANCING_SEGMENT_CROSSED). Group and post one journal per
        // distinct segment value instead.
        if (parsed.balancingDimension() != null) {
            return importGroupedByBalancingSegment(preview, parsed, parsed.balancingDimension(),
                    legalEntityId, ledgerId, accountingPeriodId, createdBy);
        }
        return importSingleJournal(preview, parsed, legalEntityId, ledgerId, accountingPeriodId, createdBy);
    }

    private OpeningBalanceImportResponse importSingleJournal(OpeningBalancePreviewResponse preview, ParsedPreview parsed,
                                                               UUID legalEntityId, UUID ledgerId,
                                                               UUID accountingPeriodId, String createdBy) {
        if (!preview.isBalanced()) {
            String message = "Opening balances do not balance. DR: " + preview.totalDr()
                    + " CR: " + preview.totalCr() + " (difference: " + preview.imbalanceAmount() + ")";
            return OpeningBalanceImportResponse.builder()
                    .success(false)
                    .totalLines(preview.totalLines())
                    .postedLines(0)
                    .journalCount(0)
                    .journals(List.of())
                    .message(message)
                    .errors(List.of(message))
                    .build();
        }

        List<AieLineRequest> lines = buildLineRequests(preview.lines(), parsed.parsedLines(),
                indexRange(preview.lines().size()));

        AieImportRequest request = new AieImportRequest(
                "OB-" + UUID.randomUUID(),
                SOURCE_SYSTEM,
                legalEntityId,
                ledgerId,
                accountingPeriodId,
                null,
                "Opening Balance Import",
                createdBy,
                lines);

        AieImportResponse pipelineResponse = aiePipelineService.ingest(request, JOURNAL_SOURCE_CODE, JOURNAL_CATEGORY_CODE);
        boolean posted = "POSTED".equals(pipelineResponse.status());

        OpeningBalanceJournalResult journalResult = OpeningBalanceJournalResult.builder()
                .segmentValue(null)
                .journalHeaderId(pipelineResponse.journalHeaderId())
                .journalNumber(pipelineResponse.journalNumber())
                .lineCount(preview.lines().size())
                .success(posted)
                .message(pipelineResponse.message())
                .build();

        return OpeningBalanceImportResponse.builder()
                .success(posted)
                .journalHeaderId(pipelineResponse.journalHeaderId())
                .journalNumber(pipelineResponse.journalNumber())
                .totalLines(preview.totalLines())
                .postedLines(posted ? preview.validLines() : 0)
                .journalCount(posted ? 1 : 0)
                .journals(List.of(journalResult))
                .message(pipelineResponse.message())
                .errors(pipelineResponse.errors().stream().map(AieLineErrorResponse::errorMessage).toList())
                .build();
    }

    // Groups lines by the value of the Ledger's 2nd balancing segment
    // (e.g. UNIT/Business Unit) and posts one journal per distinct value —
    // each group must independently balance (DR=CR within that group) before
    // ANY journal is posted, matching the existing all-or-nothing semantics
    // of importSingleJournal's overall-balance check.
    private OpeningBalanceImportResponse importGroupedByBalancingSegment(OpeningBalancePreviewResponse preview,
            ParsedPreview parsed, FinanceDimension balancingDimension, UUID legalEntityId, UUID ledgerId,
            UUID accountingPeriodId, String createdBy) {

        String dimensionKey = balancingDimension.getDimensionType().name();

        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        List<String> groupingErrors = new ArrayList<>();
        for (int i = 0; i < parsed.parsedLines().size(); i++) {
            ParsedLine line = parsed.parsedLines().get(i);
            String value = line.dimensionValues().get(dimensionKey);
            if (value == null || value.isBlank()) {
                groupingErrors.add("Line " + line.lineNumber() + ": " + balancingDimension.getName()
                        + " is required to group Opening Balance lines by balancing segment.");
                continue;
            }
            groups.computeIfAbsent(value, k -> new ArrayList<>()).add(i);
        }

        if (!groupingErrors.isEmpty()) {
            return OpeningBalanceImportResponse.builder()
                    .success(false)
                    .totalLines(preview.totalLines())
                    .postedLines(0)
                    .journalCount(0)
                    .journals(List.of())
                    .message(groupingErrors.size() + " line(s) missing the balancing segment value. No journal was posted.")
                    .errors(groupingErrors)
                    .build();
        }

        List<String> imbalanceErrors = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            BigDecimal groupDr = BigDecimal.ZERO;
            BigDecimal groupCr = BigDecimal.ZERO;
            for (int idx : entry.getValue()) {
                OpeningBalancePreviewLine line = preview.lines().get(idx);
                groupDr = groupDr.add(line.drAmount());
                groupCr = groupCr.add(line.crAmount());
            }
            if (groupDr.compareTo(groupCr) != 0) {
                imbalanceErrors.add(balancingDimension.getName() + " " + entry.getKey() + " does not balance. DR: "
                        + groupDr + " CR: " + groupCr + " (difference: " + groupDr.subtract(groupCr).abs() + ")");
            }
        }

        if (!imbalanceErrors.isEmpty()) {
            return OpeningBalanceImportResponse.builder()
                    .success(false)
                    .totalLines(preview.totalLines())
                    .postedLines(0)
                    .journalCount(0)
                    .journals(List.of())
                    .message("Opening balances do not balance within one or more " + balancingDimension.getName()
                            + " segments. No journal was posted.")
                    .errors(imbalanceErrors)
                    .build();
        }

        List<OpeningBalanceJournalResult> journalResults = new ArrayList<>();
        List<String> postingErrors = new ArrayList<>();
        int postedLines = 0;
        boolean allPosted = true;

        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            String segmentValue = entry.getKey();
            List<Integer> indices = entry.getValue();

            List<AieLineRequest> lines = buildLineRequests(preview.lines(), parsed.parsedLines(), indices);

            AieImportRequest request = new AieImportRequest(
                    "OB-" + UUID.randomUUID(),
                    SOURCE_SYSTEM,
                    legalEntityId,
                    ledgerId,
                    accountingPeriodId,
                    null,
                    "Opening Balance Import — " + balancingDimension.getName() + " " + segmentValue,
                    createdBy,
                    lines);

            AieImportResponse pipelineResponse = aiePipelineService.ingest(request, JOURNAL_SOURCE_CODE, JOURNAL_CATEGORY_CODE);
            boolean posted = "POSTED".equals(pipelineResponse.status());

            journalResults.add(OpeningBalanceJournalResult.builder()
                    .segmentValue(segmentValue)
                    .journalHeaderId(pipelineResponse.journalHeaderId())
                    .journalNumber(pipelineResponse.journalNumber())
                    .lineCount(indices.size())
                    .success(posted)
                    .message(pipelineResponse.message())
                    .build());

            if (posted) {
                postedLines += indices.size();
            } else {
                allPosted = false;
                postingErrors.add(balancingDimension.getName() + " " + segmentValue + ": " + pipelineResponse.message());
                postingErrors.addAll(pipelineResponse.errors().stream().map(AieLineErrorResponse::errorMessage).toList());
                // Stop at the first posting failure — journals already posted for
                // earlier groups remain posted (each is its own committed AIE
                // batch/journal), so continuing would only obscure which groups
                // succeeded. The response's journals[] shows exactly what happened.
                break;
            }
        }

        int postedJournalCount = (int) journalResults.stream().filter(OpeningBalanceJournalResult::success).count();

        return OpeningBalanceImportResponse.builder()
                .success(allPosted)
                .journalHeaderId(journalResults.size() == 1 ? journalResults.get(0).journalHeaderId() : null)
                .journalNumber(journalResults.size() == 1 ? journalResults.get(0).journalNumber() : null)
                .totalLines(preview.totalLines())
                .postedLines(postedLines)
                .journalCount(postedJournalCount)
                .journals(journalResults)
                .message(allPosted
                        ? "Posted " + postedJournalCount + " journal(s), one per " + balancingDimension.getName() + "."
                        : postedJournalCount + " journal(s) posted before a failure stopped the import.")
                .errors(postingErrors)
                .build();
    }

    private List<AieLineRequest> buildLineRequests(List<OpeningBalancePreviewLine> previewLines,
                                                     List<ParsedLine> parsedLines, List<Integer> indices) {
        List<AieLineRequest> lines = new ArrayList<>();
        for (int seq = 0; seq < indices.size(); seq++) {
            int idx = indices.get(seq);
            OpeningBalancePreviewLine line = previewLines.get(idx);
            Map<String, String> combination = new HashMap<>(parsedLines.get(idx).dimensionValues());
            combination.put(DimensionType.NATURAL_ACCOUNT.name(), line.accountCode());
            // gl.journal_line's ck_debit_or_credit constraint requires the
            // non-applicable side to be NULL, not zero — the preview DTO keeps
            // 0 for display, but the pipeline must receive null here.
            lines.add(new AieLineRequest(
                    seq + 1, line.accountCode(), combination,
                    nullIfZero(line.drAmount()), nullIfZero(line.crAmount()), line.description(),
                    null, null, null, null));
        }
        return lines;
    }

    private List<Integer> indexRange(int size) {
        List<Integer> indices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            indices.add(i);
        }
        return indices;
    }

    private BigDecimal nullIfZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0 ? null : value;
    }

    // ── shared parse + validate + classify ──────────────────────────────────

    private record ParsedLine(int lineNumber, String accountCode, Map<String, String> dimensionValues,
                               BigDecimal balance, String description) {
    }

    private record ParsedPreview(List<ParsedLine> parsedLines, OpeningBalancePreviewResponse response,
                                  FinanceDimension balancingDimension) {
    }

    private ParsedPreview buildPreview(MultipartFile file, UUID ledgerId) throws IOException {
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", ledgerId));

        FinanceDimension naturalAcctDim = financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT)
                .orElseThrow(() -> new EvyoogException("NO_NATURAL_ACCOUNT_DIM",
                        "No Natural Account dimension found for this Ledger.", HttpStatus.BAD_REQUEST));

        List<FinanceDimension> otherDimensions = ledger.getCoaStructure() == null
                ? List.of()
                : financeDimensionRepository
                        .findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(ledger.getCoaStructure().getId())
                        .stream()
                        .filter(d -> d.getDimensionType() != DimensionType.NATURAL_ACCOUNT)
                        .toList();

        // The 1st balancing segment (Legal Entity) is implicit and already
        // scoped by legalEntityId on every GL table — only a configured 2nd
        // balancing segment (e.g. Business Unit) requires OB import to group
        // lines into separate journals. See PostingEngine Rule 11.
        FinanceDimension balancingDimension = ledger.getCoaStructure() == null
                ? null
                : financeDimensionRepository
                        .findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(ledger.getCoaStructure().getId())
                        .stream()
                        .filter(d -> d.getBalancingSequence() != null && d.getBalancingSequence() == 2)
                        .findFirst()
                        .orElse(null);

        List<ParsedLine> parsedLines = parseRows(file, otherDimensions);

        List<OpeningBalancePreviewLine> previewLines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        BigDecimal totalDr = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;
        int errorLines = 0;

        for (ParsedLine parsed : parsedLines) {
            List<String> lineErrors = new ArrayList<>();

            if (parsed.accountCode() == null || parsed.accountCode().isBlank()) {
                lineErrors.add("Account code is required");
            }
            if (parsed.balance() == null || parsed.balance().compareTo(BigDecimal.ZERO) <= 0) {
                lineErrors.add("Balance must be a positive amount");
            }

            DimensionValue account = null;
            if (parsed.accountCode() != null && !parsed.accountCode().isBlank()) {
                account = dimensionValueRepository
                        .findByFinanceDimensionIdAndCodeAndIsActiveTrue(naturalAcctDim.getId(), parsed.accountCode())
                        .orElse(null);
                if (account == null) {
                    lineErrors.add("Account not found: " + parsed.accountCode());
                } else if (account.getNormalBalance() == null) {
                    lineErrors.add("Account has no normal balance configured: " + parsed.accountCode());
                }
            }

            for (FinanceDimension dimension : otherDimensions) {
                String value = parsed.dimensionValues().get(dimension.getDimensionType().name());
                if (value == null) {
                    continue;
                }
                boolean exists = dimensionValueRepository
                        .findByFinanceDimensionIdAndCodeAndIsActiveTrue(dimension.getId(), value)
                        .isPresent();
                if (!exists) {
                    lineErrors.add(dimension.getName() + " value not found: " + value);
                }
            }

            String accountName = null;
            String qualifier = null;
            String normalBalance = null;
            BigDecimal dr = null;
            BigDecimal cr = null;
            if (account != null) {
                accountName = account.getName();
                qualifier = account.getAccountQualifier() == null ? null : account.getAccountQualifier().name();
                if (account.getNormalBalance() != null) {
                    normalBalance = account.getNormalBalance().name();
                    if (lineErrors.isEmpty()) {
                        if (account.getNormalBalance() == NormalBalance.DR) {
                            dr = parsed.balance();
                            cr = BigDecimal.ZERO;
                        } else {
                            cr = parsed.balance();
                            dr = BigDecimal.ZERO;
                        }
                    }
                }
            }

            String errorMessage = lineErrors.isEmpty() ? null : String.join("; ", lineErrors);
            if (errorMessage != null) {
                errorLines++;
                errors.add("Line " + parsed.lineNumber() + ": " + errorMessage);
            } else {
                totalDr = totalDr.add(dr);
                totalCr = totalCr.add(cr);
            }

            previewLines.add(OpeningBalancePreviewLine.builder()
                    .lineNumber(parsed.lineNumber())
                    .accountCode(parsed.accountCode())
                    .accountName(accountName)
                    .accountQualifier(qualifier)
                    .normalBalance(normalBalance)
                    .costCentreCode(parsed.dimensionValues().get(DimensionType.COST_CENTRE.name()))
                    .productCode(parsed.dimensionValues().get(DimensionType.PRODUCT.name()))
                    .balance(parsed.balance())
                    .drAmount(dr)
                    .crAmount(cr)
                    .description(parsed.description())
                    .errorMessage(errorMessage)
                    .build());
        }

        int totalLines = parsedLines.size();
        int validLines = totalLines - errorLines;

        OpeningBalancePreviewResponse response = OpeningBalancePreviewResponse.builder()
                .totalLines(totalLines)
                .validLines(validLines)
                .errorLines(errorLines)
                .totalDr(totalDr)
                .totalCr(totalCr)
                .isBalanced(totalDr.compareTo(totalCr) == 0)
                .imbalanceAmount(totalDr.subtract(totalCr).abs())
                .lines(previewLines)
                .errors(errors)
                .build();

        return new ParsedPreview(parsedLines, response, balancingDimension);
    }

    private List<ParsedLine> parseRows(MultipartFile file, List<FinanceDimension> dimensions) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new EvyoogException("EMPTY_FILE", "Excel file has no header row.", HttpStatus.BAD_REQUEST);
            }
            Map<String, Integer> colIndex = buildColumnIndex(headerRow);

            List<ParsedLine> lines = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isEmptyRow(row)) {
                    continue;
                }

                String accountCode = getCellString(row, colIndex, "accountcode");

                Map<String, String> dimensionValues = new HashMap<>();
                for (FinanceDimension dimension : dimensions) {
                    Integer colIdx = colIndex.get(normalise(dimension.getCode()));
                    if (colIdx == null) {
                        continue;
                    }
                    String value = getCellStringAt(row, colIdx);
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    dimensionValues.put(dimension.getDimensionType().name(), value);
                }

                BigDecimal balance = getCellBigDecimal(row, colIndex, "balance");
                String description = getCellString(row, colIndex, "description");

                lines.add(new ParsedLine(lines.size() + 1, accountCode, dimensionValues, balance, description));
            }
            return lines;
        }
    }

    private List<FinanceDimension> resolveNonNaturalDimensions(UUID ledgerId) {
        if (ledgerId == null) {
            return List.of();
        }
        return ledgerRepository.findById(ledgerId)
                .map(Ledger::getCoaStructure)
                .map(coaStructure -> financeDimensionRepository
                        .findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(coaStructure.getId()))
                .orElse(List.of())
                .stream()
                .filter(d -> d.getDimensionType() != DimensionType.NATURAL_ACCOUNT)
                .toList();
    }

    private String exampleValueFor(DimensionType type) {
        return switch (type) {
            case COST_CENTRE -> "CC-ADM";
            case PROFIT_CENTRE -> "PC-NORTH";
            case PROJECT -> "PROJ-001";
            default -> "";
        };
    }

    private Map<String, Integer> buildColumnIndex(Row headerRow) {
        Map<String, Integer> index = new HashMap<>();
        for (Cell cell : headerRow) {
            String header = normalise(cell.getStringCellValue());
            if (!header.isBlank()) {
                index.put(header, cell.getColumnIndex());
            }
        }
        return index;
    }

    private String normalise(String header) {
        return header.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String getCellString(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        return idx == null ? null : getCellStringAt(row, idx);
    }

    private String getCellStringAt(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> {
                String value = cell.getStringCellValue().trim();
                yield value.isBlank() ? null : value;
            }
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private BigDecimal getCellBigDecimal(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        Cell cell = idx == null ? null : row.getCell(idx);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        if (cell.getCellType() == CellType.STRING) {
            String value = cell.getStringCellValue().trim();
            if (value.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private boolean isEmptyRow(Row row) {
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }
}
