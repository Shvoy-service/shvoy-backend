package com.shvoy;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place every error response is shaped — see ErrorCode for the
 * catalogue and ErrorResponse for the body shape. Controllers never format
 * their own errors: they throw an ApiException subclass (or let validation/
 * authorization exceptions propagate from framework code) and this is the
 * only place that turns either into a response body.
 *
 * Authentication failures (401) are handled separately, in
 * ApiAuthenticationEntryPoint — those occur inside Spring Security's filter
 * chain, before a request ever reaches a controller, so they never arrive
 * here regardless of what's registered on this class.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return respond(ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
        return respond(ErrorCode.VALIDATION_ERROR, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(ErrorCode.VALIDATION_ERROR, "Malformed request body", request);
    }

    /**
     * Method-security denials (@PreAuthorize) throw this from inside the
     * controller invocation itself, so — unlike a plain authentication
     * failure — it does reach here rather than Spring Security's own
     * filter-level handling.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(ErrorCode.FORBIDDEN, "Access denied", request);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message, request.getRequestURI()));
    }
}
