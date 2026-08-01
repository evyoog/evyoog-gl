package com.evyoog.gl.posting.service;

import com.evyoog.gl.aie.repository.SlaEventLogRepository;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.posting.domain.AccountBalance;
import com.evyoog.gl.posting.domain.JournalHeader;
import com.evyoog.gl.posting.domain.JournalStatus;
import com.evyoog.gl.posting.dto.PostingLineRequest;
import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.dto.PostingResult;
import com.evyoog.gl.posting.repository.AccountBalanceRepository;
import com.evyoog.gl.posting.repository.JournalCategoryRepository;
import com.evyoog.gl.posting.repository.JournalHeaderRepository;
import com.evyoog.gl.posting.repository.JournalSourceRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PostingEngineIT {

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
    @Autowired private JournalHeaderRepository journalHeaderRepository;
    @Autowired private AccountBalanceRepository accountBalanceRepository;
    @Autowired private JournalSourceRepository journalSourceRepository;
    @Autowired private JournalCategoryRepository journalCategoryRepository;
    @Autowired private SlaEventLogRepository slaEventLogRepository;

    @Test
    void testPostThick_fullLifecycle_journalAndBalanceCreated() throws Exception {
        Fixture fx = buildFixture("THICK");
        openPeriod(fx);

        PostingResult result = postingEngine.post(balancedRequest(fx));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getModeUsed()).isEqualTo(FinanceMode.THICK);
        assertThat(result.getJournalNumber()).matches("JE-\\d{4}-\\d{5}");

        JournalHeader saved = journalHeaderRepository.findById(result.getJournalHeaderId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(saved.getFinanceModeSnapshot()).isEqualTo(FinanceMode.THICK);
        assertThat(saved.getTotalDebit()).isEqualByComparingTo("100.00");
        assertThat(saved.getTotalCredit()).isEqualByComparingTo("100.00");

        List<AccountBalance> balances = accountBalanceRepository
                .findByLedgerIdAndLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(
                        fx.ledgerId, fx.legalEntityId, fx.periodId, fx.cashAccountId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getPeriodToDateDr()).isEqualByComparingTo("100.00");
    }

    @Test
    void testPostThin_fullLifecycle_journalAndBalanceCreated() throws Exception {
        Fixture fx = buildFixture("THIN");
        openPeriod(fx);

        PostingResult result = postingEngine.post(balancedRequest(fx));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getModeUsed()).isEqualTo(FinanceMode.THIN);

        JournalHeader saved = journalHeaderRepository.findById(result.getJournalHeaderId()).orElseThrow();
        assertThat(saved.getFinanceModeSnapshot()).isEqualTo(FinanceMode.THIN);

        List<AccountBalance> balances = accountBalanceRepository
                .findByLedgerIdAndLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(
                        fx.ledgerId, fx.legalEntityId, fx.periodId, fx.revenueAccountId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getPeriodToDateCr()).isEqualByComparingTo("100.00");
    }

    @Test
    void testPostThick_unbalanced_returns422() throws Exception {
        Fixture fx = buildFixture("THICK");
        openPeriod(fx);

        PostingRequest request = balancedRequest(fx);
        request.setLines(List.of(
                PostingLineRequest.builder().naturalAccountValueId(fx.cashAccountId)
                        .debitAmount(new BigDecimal("100.00")).build(),
                PostingLineRequest.builder().naturalAccountValueId(fx.revenueAccountId)
                        .creditAmount(new BigDecimal("50.00")).build()));

        assertThatThrownBy(() -> postingEngine.post(request))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "JOURNAL_NOT_BALANCED")
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void testPostThick_closedPeriod_periodNotOpen() throws Exception {
        Fixture fx = buildFixture("THICK");
        // Period is left NOT_OPENED — never call openPeriod(fx).

        assertThatThrownBy(() -> postingEngine.post(balancedRequest(fx)))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "PERIOD_NOT_OPEN");
    }

    @Test
    void testPostEventOnly_slaEventLogWritten_noJournalCreated() throws Exception {
        Fixture fx = buildFixture("EVENT_ONLY");

        PostingRequest request = balancedRequest(fx);
        request.setEventPayload(Map.of("orderId", "SO-1001", "amount", "100.00"));

        PostingResult result = postingEngine.post(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getJournalHeaderId()).isNull();
        assertThat(result.getSlaEventLogId()).isNotNull();
        assertThat(slaEventLogRepository.findById(result.getSlaEventLogId())).isPresent();
        assertThat(journalHeaderRepository.findByLegalEntityId(fx.legalEntityId)).isEmpty();
    }

    @Test
    void testOptimisticLocking_repeatedPosts_balanceAccumulatesAndVersionIncrements() throws Exception {
        Fixture fx = buildFixture("THICK");
        openPeriod(fx);

        postingEngine.post(balancedRequest(fx));
        postingEngine.post(balancedRequest(fx));

        List<AccountBalance> balances = accountBalanceRepository
                .findByLedgerIdAndLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(
                        fx.ledgerId, fx.legalEntityId, fx.periodId, fx.cashAccountId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).getPeriodToDateDr()).isEqualByComparingTo("200.00");
        assertThat(balances.get(0).getVersion()).isEqualTo(1L);
    }

    @Test
    void testPostThick_requiredCostCentrePresent_success() throws Exception {
        Fixture fx = buildFixture("THICK");
        openPeriod(fx);
        UUID costCentreDimId = createFinanceDimensionOfType(fx.ledgerId, "CC-DIM", "COST_CENTRE", true);
        createDimensionValue(costCentreDimId, "CC-MFG", null);

        PostingRequest request = balancedRequest(fx);
        request.setLines(List.of(
                PostingLineRequest.builder().naturalAccountValueId(fx.cashAccountId)
                        .accountCombination(Map.of("COST_CENTRE", "CC-MFG"))
                        .debitAmount(new BigDecimal("100.00")).build(),
                PostingLineRequest.builder().naturalAccountValueId(fx.revenueAccountId)
                        .accountCombination(Map.of("COST_CENTRE", "CC-MFG"))
                        .creditAmount(new BigDecimal("100.00")).build()));

        PostingResult result = postingEngine.post(request);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testPostThick_requiredCostCentreMissing_throwsMISSING_REQUIRED_DIMENSION() throws Exception {
        Fixture fx = buildFixture("THICK");
        openPeriod(fx);
        createFinanceDimensionOfType(fx.ledgerId, "CC-DIM", "COST_CENTRE", true);

        assertThatThrownBy(() -> postingEngine.post(balancedRequest(fx)))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MISSING_REQUIRED_DIMENSION")
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    private UUID createFinanceDimensionOfType(UUID ledgerId, String code, String dimensionType, boolean isRequired) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("ledgerId", ledgerId.toString());
        request.put("code", code);
        request.put("name", code);
        request.put("dimensionType", dimensionType);
        request.put("isRequired", isRequired);

        String response = mockMvc.perform(post("/api/v1/gl/finance-dimensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private PostingRequest balancedRequest(Fixture fx) {
        return PostingRequest.builder()
                .legalEntityId(fx.legalEntityId)
                .accountingPeriodId(fx.periodId)
                .journalSourceId(fx.journalSourceId)
                .journalCategoryId(fx.journalCategoryId)
                .description("IT journal")
                .glDate(LocalDate.now())
                .accountingDate(LocalDate.now())
                .currencyCode("INR")
                .exchangeRate(BigDecimal.ONE)
                .performedBy("it-test")
                .lines(List.of(
                        PostingLineRequest.builder().naturalAccountValueId(fx.cashAccountId)
                                .debitAmount(new BigDecimal("100.00")).build(),
                        PostingLineRequest.builder().naturalAccountValueId(fx.revenueAccountId)
                                .creditAmount(new BigDecimal("100.00")).build()))
                .build();
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

    private Fixture buildFixture(String financeMode) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, financeMode);
        assignPrimaryLedger(legalEntityId, ledgerId);
        UUID periodId = createFirstPeriod(ledgerId, suffix);
        UUID naturalAcctDimId = createFinanceDimension(ledgerId, "NA-" + suffix);
        UUID cashAccountId = createDimensionValue(naturalAcctDimId, "1000-" + suffix, "ASSET");
        UUID revenueAccountId = createDimensionValue(naturalAcctDimId, "4000-" + suffix, "REVENUE");
        UUID journalSourceId = journalSourceRepository.findByCode("MANUAL").orElseThrow().getId();
        UUID journalCategoryId = journalCategoryRepository.findByCode("ADJUSTMENT").orElseThrow().getId();

        return new Fixture(legalEntityId, ledgerId, periodId, naturalAcctDimId,
                cashAccountId, revenueAccountId, journalSourceId, journalCategoryId);
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

    private UUID createDimensionValue(UUID financeDimensionId, String code, String accountQualifier) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("financeDimensionId", financeDimensionId.toString());
        request.put("code", code);
        request.put("name", "Account " + code);
        request.put("accountQualifier", accountQualifier);
        request.put("isSummary", false);
        request.put("isPostable", true);

        String response = mockMvc.perform(post("/api/v1/gl/dimension-values")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createFirstPeriod(UUID ledgerId, String suffix) throws Exception {
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
        final UUID naturalAcctDimId;
        final UUID cashAccountId;
        final UUID revenueAccountId;
        final UUID journalSourceId;
        final UUID journalCategoryId;

        Fixture(UUID legalEntityId, UUID ledgerId, UUID periodId, UUID naturalAcctDimId,
                UUID cashAccountId, UUID revenueAccountId, UUID journalSourceId, UUID journalCategoryId) {
            this.legalEntityId = legalEntityId;
            this.ledgerId = ledgerId;
            this.periodId = periodId;
            this.naturalAcctDimId = naturalAcctDimId;
            this.cashAccountId = cashAccountId;
            this.revenueAccountId = revenueAccountId;
            this.journalSourceId = journalSourceId;
            this.journalCategoryId = journalCategoryId;
        }
    }
}
