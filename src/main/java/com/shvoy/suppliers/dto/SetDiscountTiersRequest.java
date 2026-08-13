package com.shvoy.suppliers.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Full-replace: the submitted list becomes the SkuPrice's entire tier set —
 * an empty list clears all tiers, same convention as PUT-replaces-
 * everything elsewhere in this module (see SupplierRequest,
 * PaymentTermsRequest). Wrapped in a record (rather than binding
 * {@code List<DiscountTierRequest>} directly) so {@code @Valid} reliably
 * cascades into each element — Bean Validation's cascade guarantee applies
 * to an {@code @Valid}-annotated field, not to a bare top-level List.
 */
public record SetDiscountTiersRequest(@NotNull @Valid List<DiscountTierRequest> tiers) {
}
