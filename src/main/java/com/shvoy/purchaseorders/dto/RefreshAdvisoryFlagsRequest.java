package com.shvoy.purchaseorders.dto;

import jakarta.validation.constraints.Size;

/**
 * Clear a PO's advisory flags once the loose ends land (PO-issuance gate) — an
 * optional contract reference to record (clears contract_pending), plus a
 * re-check of the supplier's compliance (clears compliance_pending if now
 * confirmed). Both cleared flags are audited.
 */
public record RefreshAdvisoryFlagsRequest(
    @Size(max = 255) String contractReference
) {
}
