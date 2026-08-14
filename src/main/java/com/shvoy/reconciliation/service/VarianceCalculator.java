package com.shvoy.reconciliation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.shvoy.reconciliation.domain.VarianceBasis;

/**
 * The variance formula, in one deterministic, side-effect-free place (Story
 * 5.3). Pure math over {@code BigDecimal} — no persistence, no Spring — so
 * the arithmetic is unit-testable in isolation and reproducible.
 *
 * <strong>The variance basis is the one open Product Owner question of
 * Feature 5's core, and it lives here, in {@link #BASIS}, so answering it
 * differently is a one-line change rather than a hunt through the comparison
 * logic</strong> (same discipline the tolerance boundary rule gets in 5.4).
 * The reference leg is settled — variance is always PI-vs-PO ("did the
 * supplier confirm a different price than we ordered at"), which is what the
 * approval gate guards — so {@code BASIS} only chooses which value on those
 * two legs is compared (per-unit price, the built default, vs line total).
 * See {@link VarianceBasis}.
 *
 * The formula is {@code ((PI − reference) / reference) × 100}, rounded
 * HALF_EVEN to 2dp <em>before</em> it's returned (and thus before it's
 * stored or compared) — per the money contract's rounding rule and the
 * round-before-compare principle, so the displayed and compared variance are
 * the same number. The result is <strong>signed</strong>: positive is an
 * increase (PI above the reference), negative a decrease — the sign is the
 * direction, load-bearing for 5.5's increase-only approval gate.
 */
final class VarianceCalculator {

    /**
     * The active variance basis — see {@link VarianceBasis}. Built as {@code
     * UNIT_PRICE} (the recommended MVP default). If the Product Owners answer
     * "line total", change only this constant; if they answer "both", that's
     * a storage-shape change to {@code ReconciliationLine}, not just this.
     */
    static final VarianceBasis BASIS = VarianceBasis.UNIT_PRICE;

    private static final int PCT_SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private VarianceCalculator() {
    }

    /**
     * The signed unit-price variance %, or null when it can't be computed —
     * a null PO/PI price, or a zero reference (division undefined; a
     * generated PO's lines always carry a positive price, so this is a
     * defensive guard, not an expected path). The caller skips this entirely
     * for a cross-currency line, where the number would be meaningless
     * without an FX rate — see {@code ReconciliationService}.
     */
    static BigDecimal unitPriceVariancePct(BigDecimal poUnitPrice, int poQuantity,
            BigDecimal piUnitPrice, int piQuantity) {
        if (poUnitPrice == null || piUnitPrice == null) {
            return null;
        }
        BigDecimal reference = basisValue(poUnitPrice, poQuantity);
        BigDecimal value = basisValue(piUnitPrice, piQuantity);
        return variancePct(reference, value);
    }

    /** The comparable value on one leg, per the active {@link #BASIS}. */
    private static BigDecimal basisValue(BigDecimal unitPrice, int quantity) {
        return switch (BASIS) {
            case UNIT_PRICE -> unitPrice;
            case LINE_TOTAL -> unitPrice.multiply(BigDecimal.valueOf(quantity));
        };
    }

    /** The signed quantity variance % (PO as reference), or null when the PO quantity is zero. */
    static BigDecimal quantityVariancePct(int poQuantity, int piQuantity) {
        return variancePct(BigDecimal.valueOf(poQuantity), BigDecimal.valueOf(piQuantity));
    }

    /** The signed absolute quantity difference, PI − PO. */
    static int quantityVarianceAbs(int poQuantity, int piQuantity) {
        return piQuantity - poQuantity;
    }

    /**
     * {@code ((value − reference) / reference) × 100}, signed, rounded
     * HALF_EVEN to 2dp in the single divide. Null when {@code reference} is
     * zero (undefined).
     */
    private static BigDecimal variancePct(BigDecimal reference, BigDecimal value) {
        if (reference.signum() == 0) {
            return null;
        }
        return value.subtract(reference).multiply(HUNDRED).divide(reference, PCT_SCALE, ROUNDING_MODE);
    }
}
