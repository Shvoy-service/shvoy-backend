package com.shvoy.suppliers.dto;

import org.springframework.modulith.NamedInterface;

/**
 * The supplier facts the PO-issuance gate needs (PO-issuance gate) — is the
 * supplier currently validated, is its compliance confirmed, and its default
 * incoterm to pre-fill. Deliberately narrow booleans/string so {@code
 * purchaseorders} never needs the suppliers module's status/compliance enums.
 */
@NamedInterface("suppliers")
public record SupplierIssuanceView(
    boolean validated,
    boolean complianceConfirmed,
    String defaultIncoterms
) {
}
