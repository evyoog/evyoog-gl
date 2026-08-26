package com.evyoog.gl.aie.excel.service;

import com.evyoog.gl.aie.dto.AieImportRequest;
import com.evyoog.gl.aie.dto.AieLineRequest;
import com.evyoog.gl.coa.domain.CoaStructure;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcelParserServiceTest {

    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private FinanceDimensionRepository financeDimensionRepository;

    // ── fixture helpers for dynamic-dimension scenarios ─────────────────────

    private ExcelParserService serviceUnderTest() {
        return new ExcelParserService(ledgerRepository, financeDimensionRepository);
    }

    private FinanceDimension dimension(String code, DimensionType type, boolean required, int order) {
        return FinanceDimension.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(code)
                .dimensionType(type)
                .isRequired(required)
                .displayOrder(order)
                .build();
    }

    private void stubOrbinoxDimensions(UUID ledgerId, List<FinanceDimension> dimensions) {
        UUID coaStructureId = UUID.randomUUID();
        CoaStructure coaStructure = CoaStructure.builder().id(coaStructureId).build();
        Ledger ledger = Ledger.builder().id(ledgerId).coaStructure(coaStructure).build();
        when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));
        when(financeDimensionRepository.findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(coaStructureId))
                .thenReturn(dimensions);
    }

    private List<FinanceDimension> orbinoxDimensions() {
        return List.of(
                dimension("NAT-ACCT", DimensionType.NATURAL_ACCOUNT, true, 1),
                dimension("COST-CTR", DimensionType.COST_CENTRE, true, 2),
                dimension("PRODUCT", DimensionType.PRODUCT, false, 3));
    }

    // ── legacy 12-column behaviour (no COA Structure resolved) ──────────────
    // Mockito's default answer returns Optional.empty() for an unstubbed
    // Optional-returning method, so an un-mocked ledgerRepository.findById(...)
    // safely resolves to "no COA Structure" (the pre-dynamic-dimension path).

    @Test
    void testParse_validExcelFile_returnsCorrectRequest() throws Exception {
        UUID legalEntityId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();

        MockMultipartFile file = workbook(headerAndOneLine("EXC-2025-APR-001", "April 2025 Expense Journal"));

        AieImportRequest request = serviceUnderTest().parse(file, legalEntityId, ledgerId, periodId,
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        assertThat(request.eventId()).isEqualTo("EXC-2025-APR-001");
        assertThat(request.sourceSystem()).isEqualTo("EXCEL_UPLOAD");
        assertThat(request.legalEntityId()).isEqualTo(legalEntityId);
        assertThat(request.ledgerId()).isEqualTo(ledgerId);
        assertThat(request.accountingPeriodId()).isEqualTo(periodId);
        assertThat(request.description()).isEqualTo("April 2025 Expense Journal");
        assertThat(request.createdBy()).isEqualTo("accountant@orbinox.com");
        assertThat(request.lines()).hasSize(1);

        AieLineRequest line = request.lines().get(0);
        assertThat(line.lineNumber()).isEqualTo(1);
        assertThat(line.accountCode()).isEqualTo("5100");
        assertThat(line.description()).isEqualTo("Raw Material Cost");
        assertThat(line.debitAmount()).isEqualByComparingTo(new BigDecimal("2200000"));
        assertThat(line.creditAmount()).isNull();
    }

    @Test
    void testParse_multipleLines_allParsedCorrectly() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        writeHeader(sheet);

        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue("EXC-002");
        row1.createCell(7).setCellValue(1);
        row1.createCell(8).setCellValue("5100");
        row1.createCell(9).setCellValue("Raw Material Cost");
        row1.createCell(10).setCellValue(2200000);

        Row row2 = sheet.createRow(2);
        row2.createCell(7).setCellValue(2);
        row2.createCell(8).setCellValue("2100");
        row2.createCell(9).setCellValue("Accounts Payable");
        row2.createCell(11).setCellValue(2200000);

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        assertThat(request.lines()).hasSize(2);
        assertThat(request.lines().get(0).accountCode()).isEqualTo("5100");
        assertThat(request.lines().get(1).accountCode()).isEqualTo("2100");
        assertThat(request.lines().get(1).creditAmount()).isEqualByComparingTo(new BigDecimal("2200000"));
    }

    @Test
    void testParse_missingEventId_generatesOne() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        writeHeader(sheet);

        Row row1 = sheet.createRow(1);
        row1.createCell(7).setCellValue(1);
        row1.createCell(8).setCellValue("5100");
        row1.createCell(10).setCellValue(1000);

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        assertThat(request.eventId()).startsWith("EXCEL-");
    }

    @Test
    void testParse_emptyRows_skipped() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        writeHeader(sheet);

        sheet.createRow(1); // blank row — no cells

        Row row2 = sheet.createRow(2);
        row2.createCell(0).setCellValue("EXC-003");
        row2.createCell(7).setCellValue(1);
        row2.createCell(8).setCellValue("5100");
        row2.createCell(10).setCellValue(1000);

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        assertThat(request.lines()).hasSize(1);
        assertThat(request.eventId()).isEqualTo("EXC-003");
    }

    @Test
    void testParse_debitAndCredit_parsedAsBigDecimal() throws Exception {
        MockMultipartFile file = workbook(headerAndOneLine("EXC-004", "desc"));

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        AieLineRequest line = request.lines().get(0);
        assertThat(line.debitAmount()).isInstanceOf(BigDecimal.class);
        assertThat(line.debitAmount()).isEqualByComparingTo(new BigDecimal("2200000"));
    }

    @Test
    void testParse_blankAmounts_returnedAsNull() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        writeHeader(sheet);

        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue("EXC-005");
        row1.createCell(7).setCellValue(1);
        row1.createCell(8).setCellValue("2100");
        // debitAmount and creditAmount left blank

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        AieLineRequest line = request.lines().get(0);
        assertThat(line.debitAmount()).isNull();
        assertThat(line.creditAmount()).isNull();
    }

    @Test
    void testGenerateTemplate_noLedgerId_returnsStaticFallbackTemplate() throws Exception {
        byte[] template = serviceUnderTest().generateTemplate(null);

        assertThat(template).isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            Sheet sheet = workbook.getSheet("Journal Lines");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("eventId");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("creditAmount");
        }
    }

    // ── dynamic-dimension template generation ────────────────────────────────

    @Test
    void testGenerateTemplate_orbinox_has3DimensionColumns() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        stubOrbinoxDimensions(ledgerId, orbinoxDimensions());

        byte[] template = serviceUnderTest().generateTemplate(ledgerId);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            Sheet sheet = workbook.getSheet("Journal Lines");
            Row header = sheet.getRow(0);

            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("lineNumber");
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("NAT-ACCT");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("COST-CTR");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("PRODUCT");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("lineDescription");
            assertThat(header.getCell(12).getStringCellValue()).isEqualTo("debitAmount");
            assertThat(header.getCell(13).getStringCellValue()).isEqualTo("creditAmount");

            assertThat(workbook.getSheet("Instructions")).isNotNull();
        }
    }

    @Test
    void testGenerateTemplate_sevenDimLedger_has7DimensionColumns() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        List<FinanceDimension> sevenDims = List.of(
                dimension("NAT-ACCT", DimensionType.NATURAL_ACCOUNT, true, 1),
                dimension("COST-CTR", DimensionType.COST_CENTRE, true, 2),
                dimension("BU", DimensionType.CUSTOM, true, 3),
                dimension("PROJECT", DimensionType.PROJECT, false, 4),
                dimension("PRODUCT", DimensionType.PRODUCT, false, 5),
                dimension("PROFIT-CTR", DimensionType.PROFIT_CENTRE, false, 6),
                dimension("IC-PARTNER", DimensionType.INTERCOMPANY, false, 7));
        stubOrbinoxDimensions(ledgerId, sevenDims);

        byte[] template = serviceUnderTest().generateTemplate(ledgerId);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            Sheet sheet = workbook.getSheet("Journal Lines");
            Row header = sheet.getRow(0);

            // 8 fixed + 7 dimension + 3 trailing = 18 columns
            assertThat(header.getCell(14).getStringCellValue()).isEqualTo("IC-PARTNER");
            assertThat(header.getCell(15).getStringCellValue()).isEqualTo("lineDescription");
            assertThat(header.getCell(16).getStringCellValue()).isEqualTo("debitAmount");
            assertThat(header.getCell(17).getStringCellValue()).isEqualTo("creditAmount");
        }
    }

    // ── dynamic-dimension parsing ─────────────────────────────────────────────

    @Test
    void testParseLines_orbinox_populatesCostCentreInCombination() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        stubOrbinoxDimensions(ledgerId, orbinoxDimensions());

        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        Row header = sheet.createRow(0);
        writeDynamicHeader(header, "NAT-ACCT", "COST-CTR", "PRODUCT");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("EXC-010");
        row.createCell(7).setCellValue(1);
        row.createCell(8).setCellValue("5100");
        row.createCell(9).setCellValue("CC-MFG");
        row.createCell(10).setCellValue("GATE-VLV");
        row.createCell(11).setCellValue("Raw Material Cost");
        row.createCell(12).setCellValue(2200000);

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        AieLineRequest line = request.lines().get(0);
        assertThat(line.accountCombination()).containsEntry("COST_CENTRE", "CC-MFG");
        assertThat(line.accountCombination()).containsEntry("PRODUCT", "GATE-VLV");
    }

    @Test
    void testParseLines_naturalAccountPopulatedAsAccountCode() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        stubOrbinoxDimensions(ledgerId, orbinoxDimensions());

        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        Row header = sheet.createRow(0);
        writeDynamicHeader(header, "NAT-ACCT", "COST-CTR", "PRODUCT");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("EXC-011");
        row.createCell(7).setCellValue(1);
        row.createCell(8).setCellValue("5100");
        row.createCell(9).setCellValue("CC-MFG");
        row.createCell(12).setCellValue(1000);

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        AieLineRequest line = request.lines().get(0);
        assertThat(line.accountCode()).isEqualTo("5100");
        assertThat(line.accountCombination()).containsEntry("NATURAL_ACCOUNT", "5100");
    }

    @Test
    void testParseLines_optionalDimensionBlank_notInCombination() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        stubOrbinoxDimensions(ledgerId, orbinoxDimensions());

        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        Row header = sheet.createRow(0);
        writeDynamicHeader(header, "NAT-ACCT", "COST-CTR", "PRODUCT");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("EXC-012");
        row.createCell(7).setCellValue(1);
        row.createCell(8).setCellValue("5100");
        row.createCell(9).setCellValue("CC-MFG");
        // PRODUCT (col 10) left blank
        row.createCell(12).setCellValue(1000);

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        AieLineRequest line = request.lines().get(0);
        assertThat(line.accountCombination()).doesNotContainKey("PRODUCT");
    }

    @Test
    void testParseLines_missingRequiredDimension_parsesWithoutIt() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        stubOrbinoxDimensions(ledgerId, orbinoxDimensions());

        // Header omits the required COST-CTR column entirely.
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        Row header = sheet.createRow(0);
        writeDynamicHeader(header, "NAT-ACCT");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("EXC-013");
        row.createCell(7).setCellValue(1);
        row.createCell(8).setCellValue("5100");
        row.createCell(10).setCellValue(1000); // debitAmount: col 9 is lineDescription since only 1 dim col

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        AieLineRequest line = request.lines().get(0);
        assertThat(line.accountCombination()).doesNotContainKey("COST_CENTRE");
        assertThat(line.accountCode()).isEqualTo("5100");
    }

    @Test
    void testParseLines_unknownHeaderColumn_ignored() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        stubOrbinoxDimensions(ledgerId, orbinoxDimensions());

        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        Row header = sheet.createRow(0);
        writeDynamicHeader(header, "NAT-ACCT", "COST-CTR", "PRODUCT");
        header.createCell(13).setCellValue("SOME_UNRECOGNISED_COLUMN");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("EXC-014");
        row.createCell(7).setCellValue(1);
        row.createCell(8).setCellValue("5100");
        row.createCell(9).setCellValue("CC-MFG");
        row.createCell(12).setCellValue(1000);
        row.createCell(13).setCellValue("ignore me");

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        assertThat(request.lines()).hasSize(1);
        assertThat(request.lines().get(0).accountCombination()).doesNotContainKey("SOME_UNRECOGNISED_COLUMN");
    }

    @Test
    void testParseLines_emptyRow_skipped() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        stubOrbinoxDimensions(ledgerId, orbinoxDimensions());

        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        Row header = sheet.createRow(0);
        writeDynamicHeader(header, "NAT-ACCT", "COST-CTR", "PRODUCT");

        sheet.createRow(1); // blank row

        Row row2 = sheet.createRow(2);
        row2.createCell(0).setCellValue("EXC-015");
        row2.createCell(7).setCellValue(1);
        row2.createCell(8).setCellValue("5100");
        row2.createCell(9).setCellValue("CC-MFG");
        row2.createCell(12).setCellValue(1000);

        MockMultipartFile file = workbook(workbook);

        AieImportRequest request = serviceUnderTest().parse(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                "accountant@orbinox.com", "EXCEL_UPLOAD");

        assertThat(request.lines()).hasSize(1);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void writeHeader(Sheet sheet) {
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
    }

    private void writeDynamicHeader(Row header, String... dimensionCodes) {
        String[] fixed = {
                "eventId", "sourceSystem", "legalEntityId", "ledgerId",
                "accountingPeriodId", "description", "createdBy", "lineNumber"
        };
        int i = 0;
        for (String col : fixed) {
            header.createCell(i++).setCellValue(col);
        }
        for (String code : dimensionCodes) {
            header.createCell(i++).setCellValue(code);
        }
        header.createCell(i++).setCellValue("lineDescription");
        header.createCell(i++).setCellValue("debitAmount");
        header.createCell(i).setCellValue("creditAmount");
    }

    private XSSFWorkbook headerAndOneLine(String eventId, String description) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
        writeHeader(sheet);

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(eventId);
        row.createCell(1).setCellValue("EXCEL_UPLOAD");
        row.createCell(5).setCellValue(description);
        row.createCell(6).setCellValue("accountant@orbinox.com");
        row.createCell(7).setCellValue(1);
        row.createCell(8).setCellValue("5100");
        row.createCell(9).setCellValue("Raw Material Cost");
        row.createCell(10).setCellValue(2200000);
        return workbook;
    }

    private MockMultipartFile workbook(XSSFWorkbook workbook) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return new MockMultipartFile("file", "journal_import.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
