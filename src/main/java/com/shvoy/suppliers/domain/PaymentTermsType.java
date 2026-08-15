package com.shvoy.suppliers.domain;

import org.springframework.modulith.NamedInterface;

/**
 * The confirmed payment-terms model (supplier remodel): a supplier's terms are
 * one of three shapes, not just a deposit percentage.
 * <ul>
 *   <li>{@code ZERO_DEPOSIT} — no deposit; the whole order is a balance
 *       (deposit_pct must be null — an explicit 0 is this type, not a
 *       {@code DEPOSIT_BALANCE} with pct 0).</li>
 *   <li>{@code DEPOSIT_BALANCE} — a deposit + a balance; requires a
 *       deposit_pct strictly between 0 and 100.</li>
 *   <li>{@code ROLLING} — a rolling-account supplier settled against a
 *       statement (deposit_pct null). Storable here; the terms-type-aware
 *       payment behaviour (no per-PO payments) is the 6.5 re-spec, out of
 *       scope for this remodel.</li>
 * </ul>
 */
@NamedInterface("payment-terms")
public enum PaymentTermsType {
    ZERO_DEPOSIT,
    DEPOSIT_BALANCE,
    ROLLING
}
