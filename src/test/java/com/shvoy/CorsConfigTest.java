package com.shvoy;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Runs under "test", which shares its CorsConfigurationSource with "local"
 * (see CorsConfig) — so this also stands in for confirming the local/Vite-
 * preview origins are wired correctly, without a running server or browser.
 * A real browser check against a deployed environment is still worth doing
 * once one exists — the Vite dev server's proxy means "works locally" during
 * day-to-day frontend dev is not evidence this config is right (requests
 * through the proxy are same-origin and never trigger CORS at all).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void preflightFromAllowedOriginIsPermitted() throws Exception {
        mockMvc.perform(preflight("http://localhost:5173"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void preflightFromDisallowedOriginIsRejected() throws Exception {
        mockMvc.perform(preflight("https://evil.example.com"))
            .andExpect(status().isForbidden());
    }

    @Test
    void correlationIdAndAuthorizationHeadersAreAllowedInPreflight() throws Exception {
        mockMvc.perform(preflight("http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Correlation-Id, Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                containsStringIgnoringCase("X-Correlation-Id")));
    }

    private static MockHttpServletRequestBuilder preflight(String origin) {
        return options("/api/onboarding/company/{companyId}/users", UUID.randomUUID())
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
    }
}
