package com.shvoy.purchaseorders.dto;

import jakarta.validation.constraints.Size;

import com.shvoy.purchaseorders.domain.Incoterms;

/**
 * Set a draft PO's issuance details (PO-issuance gate) — full-replace. Incoterms
 * are required only at generation, so nullable here; contract reference /
 * delivery address / budget code are all optional.
 */
public record UpdatePurchaseOrderDetailsRequest(
    Incoterms incoterms,
    @Size(max = 255) String contractReference,
    @Size(max = 500) String deliveryAddress,
    @Size(max = 100) String budgetCode
) {
}
