package com.shvoy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Confirms springdoc is actually wired up (dependency present, beans
 * configured, security permits it) rather than just compiling. Runs under
 * "test", which shares its permissive security chain with "local" (see
 * SecurityConfig) — so this also stands in for confirming local reachability
 * without needing a running server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void openApiSpecIsServedAndReflectsCurrentEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("SHVOY API"))
            .andExpect(jsonPath("$.paths['/api/onboarding/register']").exists())
            .andExpect(jsonPath("$.paths['/api/onboarding/company/{companyId}/users']").exists());
    }

    @Test
    void swaggerUiIsReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"));
    }
}
