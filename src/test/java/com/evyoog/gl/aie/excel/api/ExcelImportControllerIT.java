package com.evyoog.gl.aie.excel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ExcelImportControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("evyoog_gl_test")
            .withUsername("evyoog_app")
            .withPassword("evyoog_test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void testImportExcel_validFile_returnsPostedResponse() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx);

        MockMultipartFile file = journalFile("EXC-" + UUID.randomUUID(), fx.cashAccountCode, fx.revenueAccountCode,
                new BigDecimal("500.00"), new BigDecimal("500.00"));

        mockMvc.perform(multipart("/api/v1/aie/excel/import")
                        .file(file)
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("ledgerId", fx.ledgerId.toString())
                        .param("accountingPeriodId", fx.periodId.toString())
                        .param("createdBy", "excel-user"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("POSTED"))
                .andExpect(jsonPath("$.data.journalNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.validLines").value(2));
    }

    @Test
    void testImportExcel_emptyFile_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "journal_import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        mockMvc.perform(multipart("/api/v1/aie/excel/import")
                        .file(file)
                        .param("legalEntityId", UUID.randomUUID().toString())
                        .param("ledgerId", UUID.randomUUID().toString())
                        .param("accountingPeriodId", UUID.randomUUID().toString())
                        .param("createdBy", "excel-user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_FILE"));
    }

    @Test
    void testImportExcel_invalidFileType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "journal_import.txt",
                MediaType.TEXT_PLAIN_VALUE, "not an excel file".getBytes());

        mockMvc.perform(multipart("/api/v1/aie/excel/import")
                        .file(file)
                        .param("legalEntityId", UUID.randomUUID().toString())
                        .param("ledgerId", UUID.randomUUID().toString())
                        .param("accountingPeriodId", UUID.randomUUID().toString())
                        .param("createdBy", "excel-user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TYPE"));
    }

    @Test
    void testImportExcel_unbalancedLines_returnsFailed() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx);

        MockMultipartFile file = journalFile("EXC-" + UUID.randomUUID(), fx.cashAccountCode, fx.revenueAccountCode,
                new BigDecimal("500.00"), new BigDecimal("400.00"));

        mockMvc.perform(multipart("/api/v1/aie/excel/import")
                        .file(file)
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("ledgerId", fx.ledgerId.toString())
                        .param("accountingPeriodId", fx.periodId.toString())
                        .param("createdBy", "excel-user"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errors[0].errorCode").value("UNBALANCED"));
    }

    @Test
    void testImportExcel_duplicateEventId_returns409() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx);
        String eventId = "EXC-" + UUID.randomUUID();

        mockMvc.perform(multipart("/api/v1/aie/excel/import")
                        .file(journalFile(eventId, fx.cashAccountCode, fx.revenueAccountCode,
                                new BigDecimal("500.00"), new BigDecimal("500.00")))
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("ledgerId", fx.ledgerId.toString())
                        .param("accountingPeriodId", fx.periodId.toString())
                        .param("createdBy", "excel-user"))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/aie/excel/import")
                        .file(journalFile(eventId, fx.cashAccountCode, fx.revenueAccountCode,
                                new BigDecimal("500.00"), new BigDecimal("500.00")))
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("ledgerId", fx.ledgerId.toString())
                        .param("accountingPeriodId", fx.periodId.toString())
                        .param("createdBy", "excel-user"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EVENT_ID"));
    }

    @Test
    void testDownloadTemplate_returnsXlsxFile() throws Exception {
        Fixture fx = buildFixture();

        mockMvc.perform(get("/api/v1/aie/excel/template")
                        .param("ledgerId", fx.ledgerId.toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition",
                                org.hamcrest.Matchers.containsString("journal_import_template.xlsx")));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private MockMultipartFile journalFile(String eventId, String cashAccountCode, String revenueAccountCode,
                                           BigDecimal debit, BigDecimal credit) throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Journal Lines");
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

        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue(eventId);
        row1.createCell(5).setCellValue("Excel import journal");
        row1.createCell(7).setCellValue(1);
        row1.createCell(8).setCellValue(cashAccountCode);
        row1.createCell(9).setCellValue("Cash line");
        row1.createCell(10).setCellValue(debit.doubleValue());

        Row row2 = sheet.createRow(2);
        row2.createCell(7).setCellValue(2);
        row2.createCell(8).setCellValue(revenueAccountCode);
        row2.createCell(9).setCellValue("Revenue line");
        row2.createCell(11).setCellValue(credit.doubleValue());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return new MockMultipartFile("file", "journal_import.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private void openPeriod(Fixture fx) throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/gl/period-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "legalEntityId", fx.legalEntityId.toString(),
                                "accountingPeriodId", fx.periodId.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID statusId = UUID.fromString(objectMapper.readTree(createResponse).at("/data/id").asText());

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", statusId))
                .andExpect(status().isOk());
    }

    private Fixture buildFixture() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        assignPrimaryLedger(legalEntityId, ledgerId);
        UUID calendarId = createCalendar(ledgerId, suffix);
        UUID periodId = firstPeriodId(calendarId);
        UUID naturalAcctDimId = createFinanceDimension(ledgerId, "NA-" + suffix);
        String cashAccountCode = "1000-" + suffix;
        String revenueAccountCode = "4000-" + suffix;
        createDimensionValue(naturalAcctDimId, cashAccountCode, "ASSET", false);
        createDimensionValue(naturalAcctDimId, revenueAccountCode, "REVENUE", false);

        return new Fixture(legalEntityId, ledgerId, periodId, cashAccountCode, revenueAccountCode);
    }

    private UUID createFinanceDimension(UUID ledgerId, String code) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("ledgerId", ledgerId.toString());
        request.put("code", code);
        request.put("name", "Natural Account " + code);
        request.put("dimensionType", "NATURAL_ACCOUNT");
        request.put("isRequired", true);

        String response = mockMvc.perform(post("/api/v1/gl/finance-dimensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createDimensionValue(UUID financeDimensionId, String code, String accountQualifier, boolean isSummary) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("financeDimensionId", financeDimensionId.toString());
        request.put("code", code);
        request.put("name", "Account " + code);
        request.put("accountQualifier", accountQualifier);
        request.put("isSummary", isSummary);
        request.put("isPostable", !isSummary);

        String response = mockMvc.perform(post("/api/v1/gl/dimension-values")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createCalendar(UUID ledgerId, String suffix) throws Exception {
        Map<String, Object> calendarRequest = new HashMap<>();
        calendarRequest.put("ledgerId", ledgerId.toString());
        calendarRequest.put("name", "FY Calendar " + suffix);
        calendarRequest.put("initialFiscalYear", 2025);

        String response = mockMvc.perform(post("/api/v1/gl/accounting-calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(calendarRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID firstPeriodId(UUID calendarId) throws Exception {
        String periodsResponse = mockMvc.perform(get("/api/v1/gl/accounting-calendars/{calendarId}/periods", calendarId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(periodsResponse).at("/data/0/id").asText());
    }

    private void assignPrimaryLedger(UUID legalEntityId, UUID ledgerId) throws Exception {
        mockMvc.perform(post("/api/v1/gl/legal-entity-ledgers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "legalEntityId", legalEntityId.toString(),
                                "ledgerId", ledgerId.toString(),
                                "ledgerCategory", "PRIMARY"))))
                .andExpect(status().isCreated());
    }

    private UUID createLedger(String code, String financeMode) throws Exception {
        Map<String, Object> request = Map.of(
                "code", code,
                "name", "Ledger " + code,
                "financeMode", financeMode);

        String response = mockMvc.perform(post("/api/v1/gl/ledgers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createThickEsLegalEntity(String suffix) throws Exception {
        UUID contextId = createConsumptionContext("CTX-" + suffix);
        UUID businessGroupId = createBusinessGroup(contextId, "BG-" + suffix, "THICK_ES");

        Map<String, Object> request = Map.of(
                "businessGroupId", businessGroupId.toString(),
                "code", "LE-" + suffix,
                "name", "Legal Entity " + suffix);

        String response = mockMvc.perform(post("/api/v1/gl/legal-entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createConsumptionContext(String code) throws Exception {
        Map<String, Object> request = Map.of(
                "segmentType", "WORKSPACE",
                "code", code,
                "name", "Coimbatore Manufacturing Group");

        String response = mockMvc.perform(post("/api/v1/gl/consumption-contexts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createBusinessGroup(UUID contextId, String code, String esMode) throws Exception {
        Map<String, Object> request = Map.of(
                "consumptionContextId", contextId.toString(),
                "code", code,
                "name", "Coimbatore Manufacturing Group",
                "esMode", esMode,
                "defaultCurrency", "INR");

        String response = mockMvc.perform(post("/api/v1/gl/business-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private static final class Fixture {
        final UUID legalEntityId;
        final UUID ledgerId;
        final UUID periodId;
        final String cashAccountCode;
        final String revenueAccountCode;

        Fixture(UUID legalEntityId, UUID ledgerId, UUID periodId, String cashAccountCode, String revenueAccountCode) {
            this.legalEntityId = legalEntityId;
            this.ledgerId = ledgerId;
            this.periodId = periodId;
            this.cashAccountCode = cashAccountCode;
            this.revenueAccountCode = revenueAccountCode;
        }
    }
}
