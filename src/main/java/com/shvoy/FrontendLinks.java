package com.shvoy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single home of the frontend route map (Story 9.5). Emails point humans at
 * screens, and until now the backend has never needed to know where the frontend
 * lives — so this is the one place {@code shvoy.frontend.base-url} (per-profile
 * config, beside the CORS origins) turns into a real link. Treated as
 * config-not-constant for the same reason as the CORS origins: a second thing
 * that breaks silently if environments move.
 *
 * <p><strong>The routes below are frontend routes, not ours.</strong> They are
 * provisional defaults pending confirmation from the frontend — if their route
 * names differ, the links 404, and this class is the only file to change.
 */
@Component
public class FrontendLinks {

    private final String baseUrl;

    public FrontendLinks(@Value("${shvoy.frontend.base-url}") String baseUrl) {
        // Normalise once so callers never double up a slash, whatever the config carries.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * The invite-acceptance screen, carrying the raw token as a query param — the
     * <em>only</em> place the token ever appears (token hygiene, Story 2.3/9.4).
     */
    public String inviteAccept(String rawToken) {
        return baseUrl + "/invite/accept?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    /** The purchase-order detail screen. */
    public String purchaseOrder(UUID purchaseOrderId) {
        return baseUrl + "/purchase-orders/" + purchaseOrderId;
    }

    /** The reconciliation detail screen (where a routed PI is approved/rejected). */
    public String reconciliation(UUID proformaInvoiceId) {
        return baseUrl + "/reconciliation/" + proformaInvoiceId;
    }

    /** The discrepancy-case detail screen (where a blocked payment is resolved). */
    public String discrepancyCase(UUID caseId) {
        return baseUrl + "/discrepancies/" + caseId;
    }
}
