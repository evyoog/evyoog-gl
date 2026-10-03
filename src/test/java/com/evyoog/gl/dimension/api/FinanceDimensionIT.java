package com.evyoog.gl.dimension.api;

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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class FinanceDimensionIT {

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

    // DimensionType has exactly 9 values (LEGAL_ENTITY, NATURAL_ACCOUNT, COST_CENTRE,
    // PROFIT_CENTRE, INTERCOMPANY, PRODUCT, PROJECT, CUSTOM, SPARE), and FinanceDimensionService
    // rejects a second active dimension of the same type on one Ledger
    // (DUPLICATE_DIMENSION_TYPE) since account_combination's JSONB key is the
    // DimensionType name — two dimensions of the same type would silently collide on
    // that key. A THICK Ledger can therefore have at most 9 dimensions in practice
    // today, not the 15 MAX_DIMENSIONS_EXCEEDED alone would allow.
    @Test
    void createThickLedgerDimensions_oncePerType_succeedsUpToLimit() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");

        String[] allDimensionTypes = {
                "LEGAL_ENTITY", "NATURAL_ACCOUNT", "COST_CENTRE", "PROFIT_CENTRE",
                "INTERCOMPANY", "PRODUCT", "PROJECT", "CUSTOM", "SPARE"
        };
        for (int i = 0; i < allDimensionTypes.length; i++) {
            createDimension(ledgerId, "DIM-" + i, "Dimension " + i, allDimensionTypes[i])
                    .andExpect(status().isCreated());
        }

        // A 10th dimension of any type already used (CUSTOM, here) is rejected — there
        // is no 10th distinct DimensionType left to use instead.
        createDimension(ledgerId, "CUSTOM-2", "Custom Again", "CUSTOM")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DIMENSION_TYPE"));
    }

    @Test
    void thinLedgerDimensionLimit_enforced() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THIN");

        createDimension(ledgerId, "CC", "Cost Centre", "COST_CENTRE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THIN_DIMENSION_TYPE_INVALID"));

        createDimension(ledgerId, "LE", "Legal Entity", "LEGAL_ENTITY")
                .andExpect(status().isCreated());
        createDimension(ledgerId, "NA", "Natural Account", "NATURAL_ACCOUNT")
                .andExpect(status().isCreated());

        createDimension(ledgerId, "PC", "Profit Centre", "PROFIT_CENTRE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THIN_DIMENSION_LIMIT"));
    }

    @Test
    void naturalAccountAndLegalEntityUniqueness_enforced() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");

        createDimension(ledgerId, "LE", "Legal Entity", "LEGAL_ENTITY")
                .andExpect(status().isCreated());
        createDimension(ledgerId, "LE2", "Legal Entity 2", "LEGAL_ENTITY")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_DIMENSION_EXISTS"));

        createDimension(ledgerId, "NA", "Natural Account", "NATURAL_ACCOUNT")
                .andExpect(status().isCreated());
        createDimension(ledgerId, "NA2", "Natural Account 2", "NATURAL_ACCOUNT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NATURAL_ACCOUNT_DIMENSION_EXISTS"));
    }

    private org.springframework.test.web.servlet.ResultActions createDimension(
            UUID ledgerId, String code, String name, String dimensionType) throws Exception {
        Map<String, Object> request = Map.of(
                "ledgerId", ledgerId.toString(),
                "code", code,
                "name", name,
                "dimensionType", dimensionType);

        return mockMvc.perform(post("/api/v1/gl/finance-dimensions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
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
}
