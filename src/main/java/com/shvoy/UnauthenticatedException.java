package com.shvoy;

/**
 * A 401 raised from <em>inside</em> a controller/service (not the security
 * filter chain) — e.g. {@code /api/me} finding no active SHVOY profile for an
 * otherwise-valid token. Maps to {@link ErrorCode#UNAUTHENTICATED} via
 * {@code ApiExceptionHandler}, giving the same stable auth-family code the
 * filter-level {@code ApiAuthenticationEntryPoint} produces — never a 500.
 */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException(String message) {
        super(ErrorCode.UNAUTHENTICATED, message);
    }
}
