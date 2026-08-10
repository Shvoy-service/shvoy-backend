package com.shvoy;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Formats a 401 with the same ErrorResponse shape every other error path
 * uses. Wired only into the dev/prod filter chain (see SecurityConfig) —
 * local/test never reach this, since both permit every request there.
 *
 * Kept separate from ApiExceptionHandler: an AuthenticationException is
 * thrown inside Spring Security's filter chain, before the request ever
 * reaches a controller, so a @RestControllerAdvice's @ExceptionHandler
 * methods never see it — this is the actual point Spring Security calls to
 * produce a 401, not a fallback.
 */
@Component
@Profile("!local & !test")
class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ErrorResponse body = ErrorResponse.of(ErrorCode.UNAUTHENTICATED, "Authentication required",
            request.getRequestURI());
        response.setStatus(ErrorCode.UNAUTHENTICATED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
