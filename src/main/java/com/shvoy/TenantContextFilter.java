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
 * Populates {@link TenantContext} for the duration of each request.
 *
 * Under the local and test profiles, the tenant is read from the
 * {@code X-Debug-Company-Id} header, falling back to a configured default
 * (local only, via {@code tenancy.local.default-company-id}) when absent.
 *
 * Outside local/test — i.e. dev and prod — the header is ignored entirely
 * (honoring a client-supplied header with no authentication behind it would
 * be a full cross-tenant bypass), and the tenant instead comes from the
 * authenticated request: this filter is registered (see TenancyConfig) to
 * run after Spring Security's filter chain, so by the time it runs,
 * SecurityContextHolder already holds the JwtAuthenticationToken that
 * CognitoJwtAuthenticationConverter built — its {@code shvoy_company_id}
 * claim is this request's tenant. On the tenant-exempt endpoints (register/
 * activate/invite-accept — see SecurityConfig) there's no authenticated
 * principal at all, so no tenant is set there either way, which is correct:
 * those endpoints never touch tenant-scoped data.
 *
 * Requests that never touch tenant-scoped data work fine with no tenant set
 * at all; only code that calls {@link TenantContext#get()} requires it, and
 * does so loudly if it's missing.
 */
class TenantContextFilter extends OncePerRequestFilter {

    private static final String DEBUG_HEADER = "X-Debug-Company-Id";

    private final UUID localDefaultCompanyId;
    private final boolean honorDebugHeader;

    TenantContextFilter(UUID localDefaultCompanyId, boolean honorDebugHeader) {
        this.localDefaultCompanyId = localDefaultCompanyId;
        this.honorDebugHeader = honorDebugHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = honorDebugHeader ? request.getHeader(DEBUG_HEADER) : null;
        UUID companyId = header != null ? UUID.fromString(header) : localDefaultCompanyId;
        if (companyId == null) {
            companyId = companyIdFromAuthenticatedJwt();
        }
        if (companyId != null) {
            TenantContext.set(companyId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static UUID companyIdFromAuthenticatedJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return null;
        }
        String companyId = jwtAuth.getToken().getClaimAsString(CognitoJwtAuthenticationConverter.COMPANY_ID_CLAIM);
        return companyId != null ? UUID.fromString(companyId) : null;
    }
}
