package com.shvoy.payments.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.shvoy.Money;

/**
 * The PO's running position (invoice remodel) — the single, derived,
 * read-time-computed view three consumers share: the 6.5 match re-spec, the
 * Finance view, and statement reconciliation.
 *
 * <p>The three percentages are computed fresh on every read from the current
 * active invoices, PAID payments, and the GRN projection against the PO's
 * snapshot value — <strong>never cached</strong>: a cached percentage would
 * drift the first time a supersession or GRN amendment landed. Divergence
 * between the three is expected and normal (an invoice can be raised before
 * goods land, goods can land before payment) — no validation fires on it.
 *
 * <p>{@code overInvoiced} is the one isolated boundary the position surfaces:
 * cumulative active invoiced value exceeds the PO's snapshot value. It's a
 * warn-and-surface flag, not a block (the hard rule is a pending PO answer,
 * deferred to 6.5).
 *
 * <p>{@code poValue} null (and the percentages null) only for a PO with no
 * priced value — which a finalised PO never is.
 */
public record RunningPositionResponse(
    UUID purchaseOrderId,
    Money poValue,
    Money invoicedValue,
    Money paidValue,
    Money receivedValue,
    BigDecimal pctInvoiced,
    BigDecimal pctPaid,
    BigDecimal pctReceived,
    boolean overInvoiced
) {
}
