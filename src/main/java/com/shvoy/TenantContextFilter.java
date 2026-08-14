package com.shvoy;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link TenantContext} — and, since Story 4.4, {@link
 * CurrentUserContext} — for the duration of each request. Both are
 * resolved from the same sources at the same point, so one filter handles
 * both rather than two near-identical ones.
 *
 * Under the local and test profiles, the tenant is read from the
 * {@code X-Debug-Company-Id} header, falling back to a configured default
 * (local only, via {@code tenancy.local.default-company-id}) when absent;
 * the current user is read from {@code X-Debug-User-Id} with **no**
 * fallback default — unlike the company, there's no established "local
 * default user" convenience, so a caller that needs
 * {@code CurrentUserContext.get()} without supplying this header fails
 * loudly, the same as it would for a genuinely missing tenant.
 *
 * Outside local/test — i.e. dev and prod — both headers are ignored
 * entirely (honoring a client-supplied header with no authentication
 * behind it would be a full cross-tenant/cross-identity bypass), and both
 * instead come from the authenticated request: this filter is registered
 * (see TenancyConfig) to run after Spring Security's filter chain, so by
 * the time it runs, SecurityContextHolder already holds the
 * JwtAuthenticationToken that CognitoJwtAuthenticationConverter built —
 * its {@code shvoy_company_id}/{@code shvoy_user_id} claims are this
 * request's tenant/user. On the tenant-exempt endpoints (register/
 * activate/invite-accept — see SecurityConfig) there's no authenticated
 * principal at all, so neither is set there either way, which is correct:
 * those endpoints never touch tenant-scoped data or attribute an action to
 * a user.
 *
 * Requests that never touch tenant-scoped data, or never need to know the
 * current user, work fine with neither set at all; only code that calls
 * {@link TenantContext#get()}/{@link CurrentUserContext#get()} requires
 * it, and does so loudly if it's missing.
 */
class TenantContextFilter extends OncePerRequestFilter {

    private static final String DEBUG_COMPANY_HEADER = "X-Debug-Company-Id";
    private static final String DEBUG_USER_HEADER = "X-Debug-User-Id";

    private final UUID localDefaultCompanyId;
    private final boolean honorDebugHeader;

    TenantContextFilter(UUID localDefaultCompanyId, boolean honorDebugHeader) {
        this.localDefaultCompanyId = localDefaultCompanyId;
        this.honorDebugHeader = honorDebugHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String companyHeader = honorDebugHeader ? request.getHeader(DEBUG_COMPANY_HEADER) : null;
        UUID companyId = companyHeader != null ? UUID.fromString(companyHeader) : localDefaultCompanyId;
        if (companyId == null) {
            companyId = companyIdFromAuthenticatedJwt();
        }
        if (companyId != null) {
            TenantContext.set(companyId);
        }

        String userHeader = honorDebugHeader ? request.getHeader(DEBUG_USER_HEADER) : null;
        UUID userId = userHeader != null ? UUID.fromString(userHeader) : userIdFromAuthenticatedJwt();
        if (userId != null) {
            CurrentUserContext.set(userId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            CurrentUserContext.clear();
        }
    }

    private static UUID companyIdFromAuthenticatedJwt() {
        String companyId = jwtClaim(CognitoJwtAuthenticationConverter.COMPANY_ID_CLAIM);
        return companyId != null ? UUID.fromString(companyId) : null;
    }

    private static UUID userIdFromAuthenticatedJwt() {
        String userId = jwtClaim(CognitoJwtAuthenticationConverter.USER_ID_CLAIM);
        return userId != null ? UUID.fromString(userId) : null;
    }

    private static String jwtClaim(String claimName) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return null;
        }
        return jwtAuth.getToken().getClaimAsString(claimName);
    }
}
