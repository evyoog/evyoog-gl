package com.evyoog.gl.periodstatus.api;

import com.evyoog.gl.auth.domain.Role;
import com.evyoog.gl.auth.domain.User;
import com.evyoog.gl.auth.domain.UserRole;
import com.evyoog.gl.auth.repository.RoleRepository;
import com.evyoog.gl.auth.repository.UserRepository;
import com.evyoog.gl.auth.repository.UserRoleRepository;
import com.evyoog.gl.auth.service.JwtService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.periodstatus.service.PeriodStatusService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class PeriodStatusIT {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PeriodStatusService periodStatusService;

    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private LegalEntityRepository legalEntityRepository;

    @Test
    void testFullLifecycle_notOpened_open_close_lock() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        assignPrimaryLedger(legalEntityId, ledgerId);
        UUID periodId = createFirstPeriod(ledgerId, suffix);

        String createResponse = mockMvc.perform(post("/api/v1/gl/period-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "legalEntityId", legalEntityId.toString(),
                                "accountingPeriodId", periodId.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("NOT_OPENED"))
                .andReturn().getResponse().getContentAsString();

        UUID statusId = UUID.fromString(objectMapper.readTree(createResponse).at("/data/id").asText());

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", statusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.openedAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/close", statusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/lock", statusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCKED"))
                .andExpect(jsonPath("$.data.lockedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/gl/period-status/{id}", statusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
    }

    @Test
    void testInvalidTransition_returns409() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        assignPrimaryLedger(legalEntityId, ledgerId);
        UUID periodId = createFirstPeriod(ledgerId, suffix);
        UUID statusId = createPeriodStatus(legalEntityId, periodId);

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/close", statusId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD_TRANSITION"));
    }

    @Test
    void testThinModeCannotLock() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "THIN");
        assignPrimaryLedger(legalEntityId, ledgerId);
        UUID periodId = createFirstPeriod(ledgerId, suffix);
        UUID statusId = createPeriodStatus(legalEntityId, periodId);

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/future-enterable", statusId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THIN_TRANSITION_NOT_ALLOWED"));

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", statusId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/close", statusId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/lock", statusId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THIN_TRANSITION_NOT_ALLOWED"));
    }

    @Test
    void testEventOnlyCannotUsePeriodStatus() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "EVENT_ONLY");
        assignPrimaryLedger(legalEntityId, ledgerId);
        UUID periodId = createFirstPeriod(ledgerId, suffix);

        mockMvc.perform(post("/api/v1/gl/period-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "legalEntityId", legalEntityId.toString(),
                                "accountingPeriodId", periodId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ONLY_NO_PERIOD_STATUS"));
    }

    @Test
    void testValidatePeriodOpen_usedByPostingEngine() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        assignPrimaryLedger(legalEntityId, ledgerId);
        UUID periodId = createFirstPeriod(ledgerId, suffix);
        UUID statusId = createPeriodStatus(legalEntityId, periodId);

        assertThatThrownBy(() -> periodStatusService.validatePeriodOpen(legalEntityId, periodId))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "PERIOD_NOT_OPEN");

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", statusId))
                .andExpect(status().isOk());

        periodStatusService.validatePeriodOpen(legalEntityId, periodId);
        assertThat(true).isTrue();
    }

    /**
     * V33 Rule 1 — proves the /open endpoint enforces max_open_periods end to end
     * (real HTTP -> PeriodStatusController -> PeriodManagementService -> real
     * Postgres), not just at the PeriodManagementServiceTest mock level. No
     * gl.legal_entity_period_config row exists for a Legal Entity created via
     * this test's API calls, so PeriodManagementService.getOrDefault() falls back
     * to the documented default of max_open_periods=2 — the same default V33
     * seeded for every pre-existing Legal Entity (e.g. Unicon).
     */
    @Test
    void testMaxOpenPeriods_thirdPeriodRejected() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        assignPrimaryLedger(legalEntityId, ledgerId);
        List<UUID> periodIds = createPeriods(ledgerId, suffix);

        UUID period1Status = createPeriodStatus(legalEntityId, periodIds.get(0));
        UUID period2Status = createPeriodStatus(legalEntityId, periodIds.get(1));
        UUID period3Status = createPeriodStatus(legalEntityId, periodIds.get(2));

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", period1Status))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", period2Status))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        // Rule 1 (max open periods) is checked before Rule 2 (future period limit),
        // so with the default config (max_open_periods=2) this rejects on the count
        // alone regardless of how far ahead period 3 is.
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", period3Status))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAX_OPEN_PERIODS_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(containsString("Maximum open periods limit (2) reached")));
    }

    /**
     * Bug fix regression test (September 2026): reopening a CLOSED period via
     * {@code /open} used to branch straight into {@code doReopen()} without ever
     * calling {@code validateMaxOpenPeriods()} — so a caller at the max-open-periods
     * limit could silently exceed it by reopening a previously-closed period instead
     * of opening a fresh one. This reproduces that exact shape end to end (real HTTP
     * -> PeriodManagementService -> real Postgres): two periods legitimately open,
     * then a third period reopened from CLOSED must still be rejected by Rule 1.
     *
     * <p>Reopen requires the caller to resolve as GL_MANAGER-or-above via a real
     * {@code auth.user_roles} row (the shared {@code TestJwtMockMvcCustomizer}
     * superuser token has no such row — see the documented "Known IT gap" in
     * CLAUDE.md), so this test seeds one and issues its own Authorization header.
     */
    @Test
    void testMaxOpenPeriods_reopenBypass_rejectsWhenAtLimit() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID legalEntityId = createThickEsLegalEntity(suffix);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        assignPrimaryLedger(legalEntityId, ledgerId);
        // Rule 6's isCurrentFiscalYear() (unlike Rule 2) requires a period whose date
        // range actually contains today, or reopen rejects with REOPEN_PRIOR_FISCAL_YEAR
        // before Rule 1 is ever reached — use the fiscal year that brackets the real
        // "today" (2026), not the fixed 2025 most other tests in this file use.
        List<UUID> periodIds = createPeriods(ledgerId, suffix, 2026);
        String managerAuthHeader = seedManagerAndBuildAuthHeader(legalEntityId);

        UUID period1Status = createPeriodStatus(legalEntityId, periodIds.get(0));
        UUID period2Status = createPeriodStatus(legalEntityId, periodIds.get(1));
        UUID period3Status = createPeriodStatus(legalEntityId, periodIds.get(2));

        // Open period 1, open period 2 (2 open, at the default limit), close period 1
        // (no prior periods, so the closing-sequence check is a no-op) — leaves only
        // period 2 OPEN (1 open), period 1 CLOSED.
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", period1Status))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", period2Status))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/close", period1Status))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        // Open period 3 fresh (sequence: period 2 is OPEN, satisfies Rule 3a). Rule 1
        // sees count=1 (only period 2), allows it — now 2 open again (periods 2 and 3).
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", period3Status)
                        .header("Authorization", managerAuthHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        // Reopening period 1 (CLOSED) via /open must still be rejected by Rule 1 —
        // periods 2 and 3 are already open, at the max_open_periods=2 default limit.
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/open", period1Status)
                        .header("Authorization", managerAuthHeader))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAX_OPEN_PERIODS_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(containsString("Maximum open periods limit (2) reached")));

        // Same bug, same fix, via the dedicated /reopen endpoint.
        mockMvc.perform(post("/api/v1/gl/period-status/{id}/reopen", period1Status)
                        .header("Authorization", managerAuthHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reopenedBy", "prashanth", "reason", "testing"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAX_OPEN_PERIODS_EXCEEDED"));
    }

    private String seedManagerAndBuildAuthHeader(UUID legalEntityId) {
        Role managerRole = roleRepository.findByCode("GL_MANAGER")
                .orElseThrow(() -> new IllegalStateException("GL_MANAGER role not seeded"));

        User user = userRepository.save(User.builder()
                .email("it-manager-" + UUID.randomUUID() + "@evyoog.test")
                .fullName("IT Manager")
                .passwordHash("not-used-in-this-test")
                .build());

        userRoleRepository.save(UserRole.builder()
                .user(user)
                .role(managerRole)
                .legalEntity(legalEntityRepository.getReferenceById(legalEntityId))
                .assignedBy("SYSTEM")
                .build());

        String token = jwtService.generateAccessToken(user, legalEntityId, Set.of("gl:period:manage", "gl:period:view"));
        return "Bearer " + token;
    }

    private List<UUID> createPeriods(UUID ledgerId, String suffix) throws Exception {
        return createPeriods(ledgerId, suffix, 2025);
    }

    private List<UUID> createPeriods(UUID ledgerId, String suffix, int initialFiscalYear) throws Exception {
        Map<String, Object> calendarRequest = new HashMap<>();
        calendarRequest.put("ledgerId", ledgerId.toString());
        calendarRequest.put("name", "FY Calendar " + suffix);
        calendarRequest.put("initialFiscalYear", initialFiscalYear);

        String response = mockMvc.perform(post("/api/v1/gl/accounting-calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(calendarRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID calendarId = UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());

        String periodsResponse = mockMvc.perform(get("/api/v1/gl/accounting-calendars/{calendarId}/periods", calendarId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(periodsResponse).at("/data");
        List<UUID> periodIds = new ArrayList<>();
        for (JsonNode node : data) {
            periodIds.add(UUID.fromString(node.get("id").asText()));
        }
        return periodIds;
    }

    private UUID createPeriodStatus(UUID legalEntityId, UUID periodId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/gl/period-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "legalEntityId", legalEntityId.toString(),
                                "accountingPeriodId", periodId.toString()))))
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
}
