package com.evyoog.gl.period.api;

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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AccountingPeriodIT {

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

    @Test
    void generatePeriods_success_12PeriodsCreated() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix);
        UUID calendarId = createCalendar(ledgerId, 2025);

        // The Calendar's own creation already generates FY2025 — generate the next year instead.
        mockMvc.perform(post("/api/v1/gl/accounting-calendars/{calendarId}/periods/generate", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fiscalYear", 2026))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(12))
                .andExpect(jsonPath("$.data[0].name").value("APR-2026"))
                .andExpect(jsonPath("$.data[0].fiscalYear").value("2026-27"))
                .andExpect(jsonPath("$.data[11].name").value("MAR-2027"));
    }

    @Test
    void generatePeriods_duplicate_returns409() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix);
        UUID calendarId = createCalendar(ledgerId, 2025);

        mockMvc.perform(post("/api/v1/gl/accounting-calendars/{calendarId}/periods/generate", calendarId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fiscalYear", 2025))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIODS_ALREADY_EXIST"));
    }

    @Test
    void getPeriodsByCalendar_returnsSortedList() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix);
        UUID calendarId = createCalendar(ledgerId, 2025);

        // 13: the 12 REGULAR periods of FY2025-26 plus the Adjustment Period
        // (period 13) that AccountingCalendarService.create() now generates
        // automatically for a brand-new calendar's first fiscal year (V33/V34).
        String response = mockMvc.perform(get("/api/v1/gl/accounting-calendars/{calendarId}/periods", calendarId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(13))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).at("/data");
        for (int i = 0; i < data.size(); i++) {
            assertThat(data.get(i).get("periodNumber").asInt()).isEqualTo(i + 1);
        }

        // The Adjustment Period carries the same fiscalYear label ("2025-26") as
        // the fiscal year it closes out, so it's included in this filtered count too.
        mockMvc.perform(get("/api/v1/gl/accounting-calendars/{calendarId}/periods", calendarId)
                        .param("fiscalYear", "2025-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(13));

        mockMvc.perform(get("/api/v1/gl/accounting-calendars/{calendarId}/periods", calendarId)
                        .param("fiscalYear", "2099-00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void findPeriodByDate_returnsCorrectPeriod() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix);
        UUID calendarId = createCalendar(ledgerId, 2025);

        mockMvc.perform(get("/api/v1/gl/accounting-periods/find")
                        .param("calendarId", calendarId.toString())
                        .param("date", "2025-07-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("JUL-2025"))
                .andExpect(jsonPath("$.data.startDate").value("2025-07-01"))
                .andExpect(jsonPath("$.data.endDate").value("2025-07-31"));

        mockMvc.perform(get("/api/v1/gl/accounting-periods/find")
                        .param("calendarId", calendarId.toString())
                        .param("date", "2026-02-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("FEB-2026"));

        mockMvc.perform(get("/api/v1/gl/accounting-periods/find")
                        .param("calendarId", calendarId.toString())
                        .param("date", "2030-01-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));
    }

    @Test
    void generateNextFiscalYear_extendsCalendar() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix);
        UUID calendarId = createCalendar(ledgerId, 2025);

        mockMvc.perform(post("/api/v1/gl/accounting-calendars/{calendarId}/periods/generate-next", calendarId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(12))
                .andExpect(jsonPath("$.data[0].name").value("APR-2026"))
                .andExpect(jsonPath("$.data[0].fiscalYear").value("2026-27"));

        // 25: 12 REGULAR (FY2025-26) + 1 Adjustment Period (auto-generated at
        // calendar creation, V33/V34) + 12 REGULAR (FY2026-27, generate-next).
        mockMvc.perform(get("/api/v1/gl/accounting-calendars/{calendarId}/periods", calendarId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(25));
    }

    private UUID createCalendar(UUID ledgerId, int initialFiscalYear) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("ledgerId", ledgerId.toString());
        request.put("name", "FY Calendar");
        request.put("initialFiscalYear", initialFiscalYear);

        String response = mockMvc.perform(post("/api/v1/gl/accounting-calendars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private UUID createLedger(String code) throws Exception {
        Map<String, Object> request = Map.of(
                "code", code,
                "name", "Ledger " + code,
                "financeMode", "THICK");

        String response = mockMvc.perform(post("/api/v1/gl/ledgers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }
}
