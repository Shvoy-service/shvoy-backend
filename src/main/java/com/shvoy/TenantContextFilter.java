package com.shvoy;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link TenantContext} for the duration of each request.
 *
 * Under the local and test profiles only, the tenant is read from the
 * {@code X-Debug-Company-Id} header, falling back to a configured default
 * (local only, via {@code tenancy.local.default-company-id}) when absent.
 * This header is a stand-in for resolving the tenant from real Cognito JWT
 * claims, which isn't wired up yet (see SecurityConfig). Outside local/test
 * — i.e. dev and prod — the header is ignored entirely: those profiles have
 * no way to establish a tenant yet, so tenant-scoped endpoints simply fail
 * with "no tenant set" until real JWT-based resolution replaces this. That's
 * deliberate — honoring a client-supplied header with no authentication
 * behind it would be a full cross-tenant bypass anywhere it was reachable.
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
        if (companyId != null) {
            TenantContext.set(companyId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
