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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class DimensionValueIT {

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
    void createAccountHierarchy_assetRollup_succeeds() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        UUID naturalAccountId = createDimension(ledgerId, "NA-" + suffix, "Natural Account", "NATURAL_ACCOUNT");

        String rootResponse = createValue(naturalAccountId, "1000-" + suffix, "Assets", null, "ASSET")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.normalBalance").value("DR"))
                .andReturn().getResponse().getContentAsString();
        UUID rootId = UUID.fromString(objectMapper.readTree(rootResponse).at("/data/id").asText());

        createValue(naturalAccountId, "1100-" + suffix, "Cash", rootId, "ASSET")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.normalBalance").value("DR"))
                .andExpect(jsonPath("$.data.parentValueId").value(rootId.toString()));
    }

    @Test
    void naturalAccountValueWithoutQualifier_returns400() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        UUID naturalAccountId = createDimension(ledgerId, "NA-" + suffix, "Natural Account", "NATURAL_ACCOUNT");

        Map<String, Object> request = new HashMap<>();
        request.put("financeDimensionId", naturalAccountId.toString());
        request.put("code", "1000-" + suffix);
        request.put("name", "Assets");

        mockMvc.perform(post("/api/v1/gl/dimension-values")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_QUALIFIER_REQUIRED"));
    }

    @Test
    void qualifierMismatch_returns409() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        UUID naturalAccountId = createDimension(ledgerId, "NA-" + suffix, "Natural Account", "NATURAL_ACCOUNT");

        String rootResponse = createValue(naturalAccountId, "1000-" + suffix, "Assets", null, "ASSET")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID rootId = UUID.fromString(objectMapper.readTree(rootResponse).at("/data/id").asText());

        createValue(naturalAccountId, "2000-" + suffix, "Payables", rootId, "LIABILITY")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUALIFIER_MISMATCH"));
    }

    @Test
    void updateParent_toOwnDescendant_returns400CircularReference() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        UUID naturalAccountId = createDimension(ledgerId, "NA-" + suffix, "Natural Account", "NATURAL_ACCOUNT");

        String rootResponse = createValue(naturalAccountId, "1000-" + suffix, "Assets", null, "ASSET")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID rootId = UUID.fromString(objectMapper.readTree(rootResponse).at("/data/id").asText());

        String childResponse = createValue(naturalAccountId, "1100-" + suffix, "Cash", rootId, "ASSET")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID childId = UUID.fromString(objectMapper.readTree(childResponse).at("/data/id").asText());

        // Reparenting the root under its own child would close the loop root -> child -> root.
        mockMvc.perform(patch("/api/v1/gl/dimension-values/{id}", rootId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("parentValueId", childId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CIRCULAR_REFERENCE"));
    }

    @Test
    void updateParent_toSelf_returns400CircularReference() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        UUID naturalAccountId = createDimension(ledgerId, "NA-" + suffix, "Natural Account", "NATURAL_ACCOUNT");

        String rootResponse = createValue(naturalAccountId, "1000-" + suffix, "Assets", null, "ASSET")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID rootId = UUID.fromString(objectMapper.readTree(rootResponse).at("/data/id").asText());

        mockMvc.perform(patch("/api/v1/gl/dimension-values/{id}", rootId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("parentValueId", rootId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CIRCULAR_REFERENCE"));
    }

    @Test
    void updateParent_toUnrelatedValidAccount_succeeds() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID ledgerId = createLedger("LDG-" + suffix, "THICK");
        UUID naturalAccountId = createDimension(ledgerId, "NA-" + suffix, "Natural Account", "NATURAL_ACCOUNT");

        String newRootResponse = createValue(naturalAccountId, "1500-" + suffix, "Other Assets", null, "ASSET")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID newRootId = UUID.fromString(objectMapper.readTree(newRootResponse).at("/data/id").asText());

        String childResponse = createValue(naturalAccountId, "1100-" + suffix, "Cash", null, "ASSET")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID childId = UUID.fromString(objectMapper.readTree(childResponse).at("/data/id").asText());

        mockMvc.perform(patch("/api/v1/gl/dimension-values/{id}", childId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("parentValueId", newRootId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentValueId").value(newRootId.toString()));
    }

    private org.springframework.test.web.servlet.ResultActions createValue(
            UUID financeDimensionId, String code, String name, UUID parentValueId, String accountQualifier) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("financeDimensionId", financeDimensionId.toString());
        request.put("code", code);
        request.put("name", name);
        if (parentValueId != null) {
            request.put("parentValueId", parentValueId.toString());
        }
        request.put("accountQualifier", accountQualifier);

        return mockMvc.perform(post("/api/v1/gl/dimension-values")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private UUID createDimension(UUID ledgerId, String code, String name, String dimensionType) throws Exception {
        Map<String, Object> request = Map.of(
                "ledgerId", ledgerId.toString(),
                "code", code,
                "name", name,
                "dimensionType", dimensionType);

        String response = mockMvc.perform(post("/api/v1/gl/finance-dimensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
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
