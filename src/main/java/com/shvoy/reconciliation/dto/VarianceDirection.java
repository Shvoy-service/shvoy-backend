package com.shvoy.reconciliation.dto;

import java.math.BigDecimal;

/**
 * The direction of a variance, derived from the sign of the stored signed
 * percentage (Story 5.3) — surfaced explicitly in the response for display
 * and for the downstream approval gate, which treats increases and decreases
 * differently (Roadmap v2: price increases need the 2-of-N sign-off, 5.5).
 * Not stored separately from the signed percentage — the sign <em>is</em> the
 * direction — so there's nothing to drift out of sync; this is purely the
 * read-side view of it.
 */
public enum VarianceDirection {
    INCREASE,
    DECREASE,
    NONE;

    /** {@code null} when there's no variance to take a direction from (a structural or cross-currency line). */
    public static VarianceDirection of(BigDecimal signedVariancePct) {
        if (signedVariancePct == null) {
            return null;
        }
        return switch (signedVariancePct.signum()) {
            case 1 -> INCREASE;
            case -1 -> DECREASE;
            default -> NONE;
        };
    }
}
