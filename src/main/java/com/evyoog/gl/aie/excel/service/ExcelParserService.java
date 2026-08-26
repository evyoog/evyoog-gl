package com.evyoog.gl.aie.excel.service;

import com.evyoog.gl.aie.dto.AieImportRequest;
import com.evyoog.gl.aie.dto.AieLineRequest;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.FinanceDimension;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GL-17 Stage 1 adapter — parses an uploaded "Journal Lines" Excel sheet into
 * the same {@link AieImportRequest} shape GL-16's REST import accepts, so the
 * existing 4-stage AIE pipeline can be reused unchanged.
 *
 * Dimension columns (everything between the fixed header columns and the
 * trailing amount columns) are resolved dynamically from the target Ledger's
 * COA Structure — the template is customer-specific, not a fixed 12 columns.
 */
@Service
@RequiredArgsConstructor
public class ExcelParserService {

    private static final String SHEET_NAME = "Journal Lines";
    private static final String INSTRUCTIONS_SHEET_NAME = "Instructions";

    private static final List<String> FIXED_HEADER_COLUMNS = List.of(
            "eventId", "sourceSystem", "legalEntityId", "ledgerId",
            "accountingPeriodId", "description", "createdBy", "lineNumber");

    private static final List<String> TRAILING_HEADER_COLUMNS = List.of(
            "lineDescription", "debitAmount", "creditAmount");

    private final LedgerRepository ledgerRepository;
    private final FinanceDimensionRepository financeDimensionRepository;

    public AieImportRequest parse(MultipartFile file,
                                   UUID legalEntityId,
                                   UUID ledgerId,
                                   UUID accountingPeriodId,
                                   String createdBy,
                                   String sourceSystem) throws IOException {

        List<FinanceDimension> dimensions = resolveDimensions(ledgerId);

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

            String eventId = null;
            String description = null;
            UUID resolvedLegalEntityId = legalEntityId;
            List<AieLineRequest> lines = new ArrayList<>();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isEmptyRow(row)) {
                    continue;
                }

                if (eventId == null) {
                    eventId = getCellString(row, colIndex, "eventid");
                    description = getCellString(row, colIndex, "description");
                    String fileLegalEntityId = getCellString(row, colIndex, "legalentityid");
                    if (fileLegalEntityId != null && !fileLegalEntityId.isBlank()) {
                        resolvedLegalEntityId = UUID.fromString(fileLegalEntityId);
                    }
                }

                Map<String, String> combination = new HashMap<>();
                String accountCode = null;
                for (FinanceDimension dimension : dimensions) {
                    Integer colIdx = colIndex.get(normalise(dimension.getCode()));
                    if (colIdx == null) {
                        continue;
                    }
                    String value = getCellStringAt(row, colIdx);
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    combination.put(dimension.getDimensionType().name(), value);
                    if (dimension.getDimensionType() == DimensionType.NATURAL_ACCOUNT) {
                        accountCode = value;
                    }
                }
                // Backward compat: ledgers with no COA Structure (or a header still
                // using the old static "accountCode" column) fall back to it.
                if (accountCode == null) {
                    accountCode = getCellString(row, colIndex, "accountcode");
                }

                lines.add(new AieLineRequest(
                        getCellInt(row, colIndex, "linenumber", lines.size() + 1),
                        accountCode,
                        combination,
                        getCellBigDecimal(row, colIndex, "debitamount"),
                        getCellBigDecimal(row, colIndex, "creditamount"),
                        getCellString(row, colIndex, "linedescription"),
                        null,
                        null,
                        null,
                        null));
            }

            if (eventId == null || eventId.isBlank()) {
                eventId = "EXCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            }

            return new AieImportRequest(
                    eventId,
                    sourceSystem != null && !sourceSystem.isBlank() ? sourceSystem : "EXCEL_UPLOAD",
                    resolvedLegalEntityId,
                    ledgerId,
                    accountingPeriodId,
                    null,
                    description,
                    createdBy,
                    lines);
        }
    }

    /**
     * Generates a customer-specific template built from the Ledger's COA
     * Structure dimensions. Falls back to the old static 12-column template
     * when the Ledger has no COA Structure assigned (or {@code ledgerId} is
     * null) — kept for backward compatibility with callers that predate this
     * dynamic-dimension support.
     */
    public byte[] generateTemplate(UUID ledgerId) throws IOException {
        List<FinanceDimension> dimensions = resolveDimensions(ledgerId);
        return dimensions.isEmpty() ? generateStaticTemplate() : generateDynamicTemplate(dimensions);
    }

    private List<FinanceDimension> resolveDimensions(UUID ledgerId) {
        if (ledgerId == null) {
            return List.of();
        }
        return ledgerRepository.findById(ledgerId)
                .map(Ledger::getCoaStructure)
                .map(coaStructure -> financeDimensionRepository
                        .findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(coaStructure.getId()))
                .orElse(List.of());
    }

    private byte[] generateDynamicTemplate(List<FinanceDimension> dimensions) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            Row header = sheet.createRow(0);

            List<String> headers = new ArrayList<>(FIXED_HEADER_COLUMNS);
            dimensions.forEach(dimension -> headers.add(dimension.getCode()));
            headers.addAll(TRAILING_HEADER_COLUMNS);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }

            Row example = sheet.createRow(1);
            example.createCell(0).setCellValue("EXCEL-001");
            example.createCell(1).setCellValue("EXCEL_UPLOAD");
            example.createCell(7).setCellValue(1);

            int col = FIXED_HEADER_COLUMNS.size();
            for (FinanceDimension dimension : dimensions) {
                example.createCell(col++).setCellValue(exampleValueFor(dimension.getDimensionType()));
            }
            example.createCell(col++).setCellValue("Example journal line");
            example.createCell(col).setCellValue(10000);

            addInstructionsSheet(workbook, dimensions);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] generateStaticTemplate() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            Row header = sheet.createRow(0);
            String[] columns = {
                    "eventId", "sourceSystem", "legalEntityId", "ledgerId",
                    "accountingPeriodId", "description", "createdBy",
                    "lineNumber", "accountCode", "lineDescription",
                    "debitAmount", "creditAmount"
            };
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }

            Row example = sheet.createRow(1);
            example.createCell(0).setCellValue("EXCEL-001");
            example.createCell(1).setCellValue("EXCEL_UPLOAD");
            example.createCell(7).setCellValue(1);
            example.createCell(8).setCellValue("5100");
            example.createCell(9).setCellValue("Raw Material Cost");
            example.createCell(10).setCellValue(2200000);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String exampleValueFor(DimensionType type) {
        return switch (type) {
            case NATURAL_ACCOUNT -> "5100";
            case COST_CENTRE -> "CC-MFG";
            case PRODUCT -> "GATE-VLV";
            case PROFIT_CENTRE -> "PC-NORTH";
            case PROJECT -> "PROJ-001";
            default -> "VALUE-001";
        };
    }

    private void addInstructionsSheet(Workbook workbook, List<FinanceDimension> dimensions) {
        Sheet sheet = workbook.createSheet(INSTRUCTIONS_SHEET_NAME);
        int r = 0;

        r = writeInstructionRow(sheet, r, "eVyoog GL — Journal Import Template Instructions", "");
        r++;

        r = writeInstructionRow(sheet, r, "Fixed columns:", "");
        r = writeInstructionRow(sheet, r, "eventId", "Unique identifier for this import batch — must not repeat a previous import");
        r = writeInstructionRow(sheet, r, "sourceSystem", "Name of the source system, e.g. EXCEL_UPLOAD");
        r = writeInstructionRow(sheet, r, "legalEntityId", "UUID of the Legal Entity (leave blank to use the value selected on screen)");
        r = writeInstructionRow(sheet, r, "ledgerId", "UUID of the Ledger this template was generated for");
        r = writeInstructionRow(sheet, r, "accountingPeriodId", "UUID of the target Accounting Period");
        r = writeInstructionRow(sheet, r, "description", "Journal header description");
        r = writeInstructionRow(sheet, r, "createdBy", "Email or username of the preparer");
        r = writeInstructionRow(sheet, r, "lineNumber", "Sequential line number within the journal, starting at 1");
        r++;

        r = writeInstructionRow(sheet, r, "Dimension columns:", "");
        for (FinanceDimension dimension : dimensions) {
            r = writeInstructionRow(sheet, r, dimension.getCode(),
                    dimension.getName() + " (" + dimension.getDimensionType() + ", "
                            + (dimension.isRequired() ? "REQUIRED" : "optional") + ")");
        }
        r++;

        r = writeInstructionRow(sheet, r, "Trailing columns:", "");
        r = writeInstructionRow(sheet, r, "lineDescription", "Description for this journal line");
        r = writeInstructionRow(sheet, r, "debitAmount", "Debit amount (leave blank if this line is a credit)");
        r = writeInstructionRow(sheet, r, "creditAmount", "Credit amount (leave blank if this line is a debit)");
        r++;

        r = writeInstructionRow(sheet, r, "Rules:", "");
        r = writeInstructionRow(sheet, r, "Total debitAmount must equal total creditAmount across all lines in the journal.", "");
        r = writeInstructionRow(sheet, r, "Only valid, active dimension values are accepted — free text is rejected by the Posting Engine.", "");
        writeInstructionRow(sheet, r, "eventId must be unique — re-uploading the same eventId will be rejected as a duplicate.", "");
    }

    private int writeInstructionRow(Sheet sheet, int rowIndex, String col0, String col1) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(col0);
        if (!col1.isBlank()) {
            row.createCell(1).setCellValue(col1);
        }
        return rowIndex + 1;
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

    private Integer getCellInt(Row row, Map<String, Integer> colIndex, String colName, int defaultValue) {
        Cell cell = getCell(row, colIndex, colName);
        if (cell == null) {
            return defaultValue;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return (int) cell.getNumericCellValue();
        }
        if (cell.getCellType() == CellType.STRING) {
            String value = cell.getStringCellValue().trim();
            if (value.isBlank()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private BigDecimal getCellBigDecimal(Row row, Map<String, Integer> colIndex, String colName) {
        Cell cell = getCell(row, colIndex, colName);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            return value == 0 ? null : BigDecimal.valueOf(value);
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

    private Cell getCell(Row row, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        return idx == null ? null : row.getCell(idx);
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
