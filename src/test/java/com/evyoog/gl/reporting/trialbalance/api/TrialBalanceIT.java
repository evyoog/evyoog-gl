package com.evyoog.gl.reporting.trialbalance.api;

import com.evyoog.gl.posting.dto.PostingLineRequest;
import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.repository.JournalCategoryRepository;
import com.evyoog.gl.posting.repository.JournalSourceRepository;
import com.evyoog.gl.posting.service.PostingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TrialBalanceIT {

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
    @Autowired private PostingEngine postingEngine;
    @Autowired private JournalSourceRepository journalSourceRepository;
    @Autowired private JournalCategoryRepository journalCategoryRepository;

    @Test
    void testTrialBalance_milestone_drEqualsCr() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx, fx.periodId);

        // Sales journal: DR AR 11800 / CR Sales 10000 / CR GST Payable 1800
        postingEngine.post(multiLineRequest(fx,
                line(fx.arAccountId, "11800.00", null),
                line(fx.salesAccountId, null, "10000.00"),
                line(fx.gstPayableAccountId, null, "1800.00")));

        // Expense journal: DR Expense 5000 / CR Payable 5000
        postingEngine.post(multiLineRequest(fx,
                line(fx.expenseAccountId, "5000.00", null),
                line(fx.payableAccountId, null, "5000.00")));

        String response = mockMvc.perform(get("/api/v1/gl/reports/trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(response).at("/data");

        assertThat(data.get("isBalanced").asBoolean()).isTrue();
        assertThat(data.get("totalDebit").decimalValue()).isEqualByComparingTo("16800.00");
        assertThat(data.get("totalCredit").decimalValue()).isEqualByComparingTo("16800.00");

        Map<String, com.fasterxml.jackson.databind.JsonNode> byCode = new HashMap<>();
        for (var node : data.get("lines")) {
            byCode.put(node.get("accountCode").asText(), node);
        }

        assertThat(byCode.get(fx.arAccountCode).get("debitBalance").decimalValue())
                .isEqualByComparingTo("11800.00");
        assertThat(byCode.get(fx.salesAccountCode).get("creditBalance").decimalValue())
                .isEqualByComparingTo("10000.00");
        assertThat(byCode.get(fx.gstPayableAccountCode).get("creditBalance").decimalValue())
                .isEqualByComparingTo("1800.00");
        assertThat(byCode.get(fx.expenseAccountCode).get("debitBalance").decimalValue())
                .isEqualByComparingTo("5000.00");
        assertThat(byCode.get(fx.payableAccountCode).get("creditBalance").decimalValue())
                .isEqualByComparingTo("5000.00");

        System.out.println("MILESTONE ACHIEVED — Trial Balance DR = CR ✓");
    }

    @Test
    void testTrialBalance_multipleJournals_accumulatesCorrectly() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx, fx.periodId);

        postingEngine.post(multiLineRequest(fx,
                line(fx.arAccountId, "100.00", null),
                line(fx.salesAccountId, null, "100.00")));
        postingEngine.post(multiLineRequest(fx,
                line(fx.arAccountId, "50.00", null),
                line(fx.salesAccountId, null, "50.00")));

        String response = mockMvc.perform(get("/api/v1/gl/reports/trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(response).at("/data");
        assertThat(data.get("totalDebit").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(data.get("totalCredit").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(data.get("isBalanced").asBoolean()).isTrue();
    }

    @Test
    void testTrialBalance_eventOnlyLedger_returns422() throws Exception {
        Fixture fx = buildFixture("EVENT_ONLY");

        mockMvc.perform(get("/api/v1/gl/reports/trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void testTrialBalance_noJournalsPosted_returns404() throws Exception {
        Fixture fx = buildFixture();

        mockMvc.perform(get("/api/v1/gl/reports/trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testTrialBalance_sortedByAccountCode() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx, fx.periodId);

        postingEngine.post(multiLineRequest(fx,
                line(fx.arAccountId, "100.00", null),
                line(fx.salesAccountId, null, "100.00")));

        String response = mockMvc.perform(get("/api/v1/gl/reports/trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var lines = objectMapper.readTree(response).at("/data/lines");
        List<String> codes = new java.util.ArrayList<>();
        for (var node : lines) {
            codes.add(node.get("accountCode").asText());
        }
        List<String> sorted = new java.util.ArrayList<>(codes);
        java.util.Collections.sort(sorted);
        assertThat(codes).isEqualTo(sorted);
    }

    @Test
    void testHierarchicalTrialBalance_flatAccounts_returnsAllAsRootsWithZeroDepth() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx, fx.periodId);

        postingEngine.post(multiLineRequest(fx,
                line(fx.arAccountId, "100.00", null),
                line(fx.salesAccountId, null, "100.00")));

        String response = mockMvc.perform(get("/api/v1/gl/reports/hierarchical-trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(response).at("/data");
        assertThat(data.get("isBalanced").asBoolean()).isTrue();
        for (var node : data.get("lines")) {
            assertThat(node.get("depth").asInt()).isEqualTo(0);
            assertThat(node.get("children")).isEmpty();
        }
    }

    @Test
    void testHierarchicalTrialBalance_hierarchyRollup_summaryEqualsChildSum() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx, fx.periodId);

        UUID naturalAcctDimId = fx.naturalAcctDimId;
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UUID summaryId = createDimensionValue(naturalAcctDimId, "1SUM-" + suffix, "ASSET", "DR", null, true, false);
        UUID childId = createDimensionValue(naturalAcctDimId, "1CHD-" + suffix, "ASSET", "DR", summaryId, false, true);

        postingEngine.post(multiLineRequest(fx,
                line(childId, "75.00", null),
                line(fx.salesAccountId, null, "75.00")));

        String response = mockMvc.perform(get("/api/v1/gl/reports/hierarchical-trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(response).at("/data");
        com.fasterxml.jackson.databind.JsonNode summaryNode = null;
        for (var node : data.get("lines")) {
            if (node.get("accountCode").asText().equals("1SUM-" + suffix)) {
                summaryNode = node;
            }
        }
        assertThat(summaryNode).isNotNull();
        assertThat(summaryNode.get("depth").asInt()).isEqualTo(0);
        assertThat(summaryNode.get("debitBalance").decimalValue()).isEqualByComparingTo("75.00");
        assertThat(summaryNode.get("children")).hasSize(1);
        var childNode = summaryNode.get("children").get(0);
        assertThat(childNode.get("depth").asInt()).isEqualTo(1);
        assertThat(childNode.get("debitBalance").decimalValue()).isEqualByComparingTo("75.00");
    }

    private PostingLineRequest line(UUID accountId, String debit, String credit) {
        return PostingLineRequest.builder()
                .naturalAccountValueId(accountId)
                .debitAmount(debit != null ? new BigDecimal(debit) : null)
                .creditAmount(credit != null ? new BigDecimal(credit) : null)
                .build();
    }

    private PostingRequest multiLineRequest(Fixture fx, PostingLineRequest... lines) {
        return PostingRequest.builder()
                .legalEntityId(fx.legalEntityId)
                .accountingPeriodId(fx.periodId)
                .journalSourceId(fx.journalSourceId)
                .journalCategoryId(fx.journalCategoryId)
                .description("GL-22 IT journal")
                .glDate(LocalDate.now())
                .accountingDate(LocalDate.now())
                .currencyCode("INR")
                .exchangeRate(BigDecimal.ONE)
                .performedBy("it-test")
                .lines(List.of(lines))
                .build();
    }

    private void openPeriod(Fixture fx, UUID periodId) throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/gl/period-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "legalEntityId", fx.legalEntityId.toString(),
                                "accountingPeriodId", periodId.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID statusId = UUID.fromString(objectMapper.readTree(createResponse).at("/data/id").asText());

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", statusId))
                .andExpect(status().isOk());
    }

    private Fixture buildFixture() throws Exception {
        return buildFixture("THICK");
    }

    private Fixture buildFixture(String financeMode) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, financeMode);
        assignPrimaryLedger(legalEntityId, ledgerId);
        List<UUID> periodIds = createPeriods(ledgerId, suffix);
        UUID naturalAcctDimId = createFinanceDimension(ledgerId, "NA-" + suffix);

        String arCode = "1100-" + suffix;
        String salesCode = "4000-" + suffix;
        String gstPayableCode = "2100-" + suffix;
        String expenseCode = "5000-" + suffix;
        String payableCode = "2000-" + suffix;

        UUID arId = createDimensionValue(naturalAcctDimId, arCode, "ASSET", "DR");
        UUID salesId = createDimensionValue(naturalAcctDimId, salesCode, "REVENUE", "CR");
        UUID gstPayableId = createDimensionValue(naturalAcctDimId, gstPayableCode, "LIABILITY", "CR");
        UUID expenseId = createDimensionValue(naturalAcctDimId, expenseCode, "EXPENSE", "DR");
        UUID payableId = createDimensionValue(naturalAcctDimId, payableCode, "LIABILITY", "CR");

        UUID journalSourceId = journalSourceRepository.findByCode("MANUAL").orElseThrow().getId();
        UUID journalCategoryId = journalCategoryRepository.findByCode("ADJUSTMENT").orElseThrow().getId();

        return new Fixture(legalEntityId, periodIds.get(0), naturalAcctDimId,
                arId, arCode, salesId, salesCode, gstPayableId, gstPayableCode,
                expenseId, expenseCode, payableId, payableCode,
                journalSourceId, journalCategoryId);
    }

    private List<UUID> createPeriods(UUID ledgerId, String suffix) throws Exception {
        Map<String, Object> calendarRequest = new HashMap<>();
        calendarRequest.put("ledgerId", ledgerId.toString());
        calendarRequest.put("name", "FY Calendar " + suffix);
        calendarRequest.put("initialFiscalYear", 2025);

        String response = mockMvc.perform(post("/api/v1/gl/accounting-calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(calendarRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID calendarId = UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());

        String periodsResponse = mockMvc.perform(get("/api/v1/gl/accounting-calendars/{calendarId}/periods", calendarId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var periodsNode = objectMapper.readTree(periodsResponse).at("/data");
        List<UUID> periods = new java.util.ArrayList<>();
        for (var node : periodsNode) {
            periods.add(UUID.fromString(node.get("id").asText()));
        }
        return periods;
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

    private UUID createDimensionValue(UUID financeDimensionId, String code, String accountQualifier,
                                       String normalBalance) throws Exception {
        return createDimensionValue(financeDimensionId, code, accountQualifier, normalBalance, null, false, true);
    }

    private UUID createDimensionValue(UUID financeDimensionId, String code, String accountQualifier,
                                       String normalBalance, UUID parentValueId, boolean isSummary,
                                       boolean isPostable) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("financeDimensionId", financeDimensionId.toString());
        request.put("code", code);
        request.put("name", "Account " + code);
        request.put("accountQualifier", accountQualifier);
        request.put("normalBalance", normalBalance);
        request.put("isSummary", isSummary);
        request.put("isPostable", isPostable);
        if (parentValueId != null) {
            request.put("parentValueId", parentValueId.toString());
        }

        String response = mockMvc.perform(post("/api/v1/gl/dimension-values")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
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
        final UUID periodId;
        final UUID naturalAcctDimId;
        final UUID arAccountId;
        final String arAccountCode;
        final UUID salesAccountId;
        final String salesAccountCode;
        final UUID gstPayableAccountId;
        final String gstPayableAccountCode;
        final UUID expenseAccountId;
        final String expenseAccountCode;
        final UUID payableAccountId;
        final String payableAccountCode;
        final UUID journalSourceId;
        final UUID journalCategoryId;

        Fixture(UUID legalEntityId, UUID periodId, UUID naturalAcctDimId,
                UUID arAccountId, String arAccountCode,
                UUID salesAccountId, String salesAccountCode,
                UUID gstPayableAccountId, String gstPayableAccountCode,
                UUID expenseAccountId, String expenseAccountCode,
                UUID payableAccountId, String payableAccountCode,
                UUID journalSourceId, UUID journalCategoryId) {
            this.legalEntityId = legalEntityId;
            this.periodId = periodId;
            this.naturalAcctDimId = naturalAcctDimId;
            this.arAccountId = arAccountId;
            this.arAccountCode = arAccountCode;
            this.salesAccountId = salesAccountId;
            this.salesAccountCode = salesAccountCode;
            this.gstPayableAccountId = gstPayableAccountId;
            this.gstPayableAccountCode = gstPayableAccountCode;
            this.expenseAccountId = expenseAccountId;
            this.expenseAccountCode = expenseAccountCode;
            this.payableAccountId = payableAccountId;
            this.payableAccountCode = payableAccountCode;
            this.journalSourceId = journalSourceId;
            this.journalCategoryId = journalCategoryId;
        }
    }
}
