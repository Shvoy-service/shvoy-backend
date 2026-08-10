package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercised as a plain unit test (no Spring context) rather than through a
 * full request under the "test" profile: the profile that would actually
 * trigger this — see SecurityConfig — deliberately stays on the permissive
 * chain (no enforced authentication), same reasoning as
 * CognitoJwtAuthenticationConverterTest.
 */
class ApiAuthenticationEntryPointTest {

    @Test
    void commenceWritesTheStandardErrorBodyWithUnauthenticatedCode() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/onboarding/company/x/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");

        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(body).contains("\"status\":401");
        assertThat(body).contains("\"path\":\"/api/onboarding/company/x/users\"");
    }
}
