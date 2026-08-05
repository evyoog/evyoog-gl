package com.evyoog.gl.reporting.segment.api;

import com.evyoog.gl.posting.dto.PostingLineRequest;
import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.repository.JournalCategoryRepository;
import com.evyoog.gl.posting.repository.JournalSourceRepository;
import com.evyoog.gl.posting.service.PostingEngine;
import com.fasterxml.jackson.databind.JsonNode;
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
class PLBySegmentIT {

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
    void testPLBySegment_costCentrePivot_correctSegmentAmounts() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx, fx.periodId);

        // Sales in CC-SAL: DR AR 1000 / CR Sales 1000
        postingEngine.post(multiLineRequest(fx,
                lineWithCombination(fx.arAccountId, "1000.00", null, null),
                lineWithCombination(fx.salesAccountId, null, "1000.00", "CC-SAL")));

        // Expense in CC-MFG: DR Raw Material 400 / CR Payable 400
        postingEngine.post(multiLineRequest(fx,
                lineWithCombination(fx.rawMaterialId, "400.00", null, "CC-MFG"),
                lineWithCombination(fx.payableAccountId, null, "400.00", null)));

        String response = mockMvc.perform(get("/api/v1/gl/reports/pl-by-segment")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString())
                        .param("segmentType", "COST_CENTRE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(response).at("/data");

        assertThat(data.get("segmentType").asText()).isEqualTo("COST_CENTRE");
        List<String> segments = new java.util.ArrayList<>();
        data.get("segments").forEach(n -> segments.add(n.asText()));
        assertThat(segments).containsExactlyInAnyOrder("CC-MFG", "CC-SAL");

        JsonNode revenueLine = findByCode(data.get("revenueLines"), fx.salesAccountCode);
        assertThat(revenueLine.get("segmentAmounts").get("CC-SAL").decimalValue())
                .isEqualByComparingTo("1000.00");
        assertThat(revenueLine.get("segmentAmounts").get("CC-MFG").decimalValue())
                .isEqualByComparingTo("0");

        JsonNode expenseLine = findByCode(data.get("expenseLines"), fx.rawMaterialCode);
        assertThat(expenseLine.get("segmentAmounts").get("CC-MFG").decimalValue())
                .isEqualByComparingTo("400.00");

        assertThat(data.get("netIncome").get("total").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(data.get("netIncome").get("CC-SAL").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(data.get("netIncome").get("CC-MFG").decimalValue()).isEqualByComparingTo("-400.00");
    }

    @Test
    void testPLBySegment_invalidSegmentType_returns400() throws Exception {
        Fixture fx = buildFixture();

        mockMvc.perform(get("/api/v1/gl/reports/pl-by-segment")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString())
                        .param("segmentType", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testPLBySegment_eventOnlyLedger_returns422() throws Exception {
        Fixture fx = buildFixture("EVENT_ONLY");

        mockMvc.perform(get("/api/v1/gl/reports/pl-by-segment")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString())
                        .param("segmentType", "COST_CENTRE"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void testTrialBalance_costCentreFilter_returnsOnlyMatchingCombination() throws Exception {
        Fixture fx = buildFixture();
        openPeriod(fx, fx.periodId);

        postingEngine.post(multiLineRequest(fx,
                lineWithCombination(fx.arAccountId, "1000.00", null, null),
                lineWithCombination(fx.salesAccountId, null, "1000.00", "CC-SAL")));
        postingEngine.post(multiLineRequest(fx,
                lineWithCombination(fx.rawMaterialId, "400.00", null, "CC-MFG"),
                lineWithCombination(fx.payableAccountId, null, "400.00", null)));

        String response = mockMvc.perform(get("/api/v1/gl/reports/trial-balance")
                        .param("legalEntityId", fx.legalEntityId.toString())
                        .param("periodId", fx.periodId.toString())
                        .param("costCentre", "CC-SAL"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var data = objectMapper.readTree(response).at("/data");
        assertThat(data.get("segmentFilters").get("costCentre").asText()).isEqualTo("CC-SAL");

        List<String> codes = new java.util.ArrayList<>();
        data.get("lines").forEach(n -> codes.add(n.get("accountCode").asText()));
        assertThat(codes).contains(fx.salesAccountCode);
        assertThat(codes).doesNotContain(fx.rawMaterialCode);
    }

    private JsonNode findByCode(JsonNode lines, String code) {
        for (var node : lines) {
            if (node.get("accountCode").asText().equals(code)) {
                return node;
            }
        }
        throw new AssertionError("No line found for account code " + code);
    }

    private PostingLineRequest lineWithCombination(UUID accountId, String debit, String credit, String costCentre) {
        PostingLineRequest.PostingLineRequestBuilder builder = PostingLineRequest.builder()
                .naturalAccountValueId(accountId)
                .debitAmount(debit != null ? new BigDecimal(debit) : null)
                .creditAmount(credit != null ? new BigDecimal(credit) : null);
        if (costCentre != null) {
            builder.accountCombination(Map.of("COST_CENTRE", costCentre));
        }
        return builder.build();
    }

    private PostingRequest multiLineRequest(Fixture fx, PostingLineRequest... lines) {
        return PostingRequest.builder()
                .legalEntityId(fx.legalEntityId)
                .accountingPeriodId(fx.periodId)
                .journalSourceId(fx.journalSourceId)
                .journalCategoryId(fx.journalCategoryId)
                .description("GL-26 IT journal")
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
        UUID naturalAcctDimId = createFinanceDimension(ledgerId, "NA-" + suffix, "NATURAL_ACCOUNT", true);
        UUID costCentreDimId = createFinanceDimension(ledgerId, "CC-" + suffix, "COST_CENTRE", false);

        String arCode = "1100-" + suffix;
        String salesCode = "4100-" + suffix;
        String rawMaterialCode = "5100-" + suffix;
        String payableCode = "2000-" + suffix;

        UUID arId = createDimensionValue(naturalAcctDimId, arCode, "ASSET", "DR");
        UUID salesId = createDimensionValue(naturalAcctDimId, salesCode, "REVENUE", "CR");
        UUID rawMaterialId = createDimensionValue(naturalAcctDimId, rawMaterialCode, "EXPENSE", "DR");
        UUID payableId = createDimensionValue(naturalAcctDimId, payableCode, "LIABILITY", "CR");

        createCostCentreValue(costCentreDimId, "CC-SAL");
        createCostCentreValue(costCentreDimId, "CC-MFG");

        UUID journalSourceId = journalSourceRepository.findByCode("MANUAL").orElseThrow().getId();
        UUID journalCategoryId = journalCategoryRepository.findByCode("ADJUSTMENT").orElseThrow().getId();

        return new Fixture(legalEntityId, periodIds.get(0),
                arId, salesCode, salesId, rawMaterialCode, rawMaterialId, payableId,
                journalSourceId, journalCategoryId);
    }

    private void createCostCentreValue(UUID financeDimensionId, String code) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("financeDimensionId", financeDimensionId.toString());
        request.put("code", code);
        request.put("name", "Cost Centre " + code);
        request.put("isSummary", false);
        request.put("isPostable", true);

        mockMvc.perform(post("/api/v1/gl/dimension-values")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
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

    private UUID createFinanceDimension(UUID ledgerId, String code, String dimensionType, boolean isRequired)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("ledgerId", ledgerId.toString());
        request.put("code", code);
        request.put("name", dimensionType + " " + code);
        request.put("dimensionType", dimensionType);
        request.put("isRequired", isRequired);

        String response = mockMvc.perform(post("/api/v1/gl/finance-dimensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createDimensionValue(UUID financeDimensionId, String code, String accountQualifier,
                                       String normalBalance) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("financeDimensionId", financeDimensionId.toString());
        request.put("code", code);
        request.put("name", "Account " + code);
        request.put("accountQualifier", accountQualifier);
        request.put("normalBalance", normalBalance);
        request.put("isSummary", false);
        request.put("isPostable", true);

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
        final UUID arAccountId;
        final String salesAccountCode;
        final UUID salesAccountId;
        final String rawMaterialCode;
        final UUID rawMaterialId;
        final UUID payableAccountId;
        final UUID journalSourceId;
        final UUID journalCategoryId;

        Fixture(UUID legalEntityId, UUID periodId,
                UUID arAccountId, String salesAccountCode, UUID salesAccountId,
                String rawMaterialCode, UUID rawMaterialId, UUID payableAccountId,
                UUID journalSourceId, UUID journalCategoryId) {
            this.legalEntityId = legalEntityId;
            this.periodId = periodId;
            this.arAccountId = arAccountId;
            this.salesAccountCode = salesAccountCode;
            this.salesAccountId = salesAccountId;
            this.rawMaterialCode = rawMaterialCode;
            this.rawMaterialId = rawMaterialId;
            this.payableAccountId = payableAccountId;
            this.journalSourceId = journalSourceId;
            this.journalCategoryId = journalCategoryId;
        }
    }
}
