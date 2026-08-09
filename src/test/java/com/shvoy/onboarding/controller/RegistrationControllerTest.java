package com.shvoy.onboarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.shvoy.LogCapture;
import com.shvoy.onboarding.service.RegistrationService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE '%@registration-test.example.com'");
        jdbcTemplate.update("DELETE FROM companies WHERE name LIKE 'Test Co %'");
    }

    @Test
    void registeringNewCompanyReturns201AndCreatesPendingAdmin() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/onboarding/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"companyName\":\"Test Co Alpha\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.companyId").exists())
            .andExpect(jsonPath("$.userId").exists())
            .andExpect(jsonPath("$.verificationRequired").value(true));

        String role = jdbcTemplate.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email);
        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
        assertThat(role).isEqualTo("ADMIN");
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        String email = uniqueEmail();
        String body = "{\"email\":\"" + email + "\",\"companyName\":\"Test Co Beta\"}";

        mockMvc.perform(post("/api/onboarding/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/onboarding/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("Beta", "Gamma")))
            .andExpect(status().isConflict());
    }

    @Test
    void malformedEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/onboarding/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"companyName\":\"Test Co Delta\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void emptyCompanyNameReturns400() throws Exception {
        mockMvc.perform(post("/api/onboarding/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + uniqueEmail() + "\",\"companyName\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void activatingWithValidTokenSetsPasswordAndActivates() throws Exception {
        String email = uniqueEmail();
        String token;
        try (LogCapture logs = new LogCapture(RegistrationService.class)) {
            mockMvc.perform(post("/api/onboarding/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"companyName\":\"Test Co Epsilon\"}"))
                .andExpect(status().isCreated());
            token = LogCapture.valueAfter(logs.firstMessageContaining("Verification link for " + email), "token=");
        }

        String storedToken = jdbcTemplate.queryForObject(
            "SELECT verification_token FROM users WHERE email = ?", String.class, email);
        assertThat(storedToken).isNotEqualTo(token);

        mockMvc.perform(post("/api/onboarding/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"correct horse battery\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
        String cognitoSub = jdbcTemplate.queryForObject(
            "SELECT cognito_sub FROM users WHERE email = ?", String.class, email);
        assertThat(status).isEqualTo("ACTIVE");
        assertThat(cognitoSub).isNotBlank();
    }

    @Test
    void activatingWithInvalidTokenReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/onboarding/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + UUID.randomUUID() + "\",\"password\":\"correct horse battery\"}"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(""));
    }

    @Test
    void weakPasswordOnActivateReturns400() throws Exception {
        String email = uniqueEmail();
        String token;
        try (LogCapture logs = new LogCapture(RegistrationService.class)) {
            mockMvc.perform(post("/api/onboarding/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"companyName\":\"Test Co Zeta\"}"))
                .andExpect(status().isCreated());
            token = LogCapture.valueAfter(logs.firstMessageContaining("Verification link for " + email), "token=");
        }

        mockMvc.perform(post("/api/onboarding/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
    }

    private static String uniqueEmail() {
        return "admin+" + UUID.randomUUID() + "@registration-test.example.com";
    }
}
