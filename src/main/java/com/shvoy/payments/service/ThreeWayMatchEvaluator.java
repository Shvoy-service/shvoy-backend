package com.shvoy.payments.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.UnitPrice;

/**
 * The three-way match rule itself (Story 6.5), pure and Spring-free so it's
 * exhaustively unit-testable — same discipline as {@code VarianceCalculator}
 * (5.3) and {@code ToleranceEvaluator} (5.4). It runs only once every leg is
 * present; missing-leg ("awaiting X") handling is the service's job.
 *
 * <p><strong>The reference basis is (b):</strong> expected amount = the
 * confirmed PI's unit prices × the <em>GRN received</em> quantities, minus any
 * validated credit. This makes the GRN leg <em>mean</em> something in the money
 * check — it catches "invoiced for what was ordered, but short-shipped", a core
 * failure mode (the ledger's first example cause). See docs/CONTRACT.md.
 *
 * <p><strong>Money equality is exact</strong> — 2dp, no tolerance. The
 * tolerance band was a <em>reconciliation</em> concept for negotiating reality;
 * the invoice is the final ask and either equals the agreed position or it
 * doesn't. A penny off is a fail that routes to a human (6.6). Deliberate
 * asymmetry with reconciliation, documented.
 *
 * <p>All failing legs are collected (not just the first) so Screen 6's
 * side-by-side and 6.6's routing see the full picture.
 */
final class ThreeWayMatchEvaluator {

    private ThreeWayMatchEvaluator() {
    }

    static MatchVerdict evaluate(MatchInputs in) {
        List<String> failures = new ArrayList<>();

        boolean currencyOk = in.piCurrency().equals(in.invoiceAmount().currency());
        if (!currencyOk) {
            failures.add("Currency: PI is " + in.piCurrency() + " but invoice is " + in.invoiceAmount().currency());
        }

        // Quantity chain: PO qty = PI qty = GRN qty, per SKU (absent in a leg counts as a mismatch).
        Set<UUID> skus = new LinkedHashSet<>();
        in.piLines().forEach(line -> skus.add(line.skuId()));
        skus.addAll(in.poQuantities().keySet());
        skus.addAll(in.grnQuantities().keySet());
        for (UUID sku : skus) {
            Integer po = in.poQuantities().get(sku);
            Integer pi = in.piQuantity(sku);
            Integer grn = in.grnQuantities().get(sku);
            if (po == null || pi == null || grn == null || !po.equals(pi) || !pi.equals(grn)) {
                failures.add("Quantity SKU " + sku + ": PO=" + po + " PI=" + pi + " GRN=" + grn);
            }
        }

        // Credit: an unagreed claimed deduction is exactly what the ledger exists to catch.
        if (in.claimedCredit() != null && !in.creditValid()) {
            failures.add("Claimed credit " + money(in.claimedCredit())
                + " does not match an open ledger entry (" + in.creditOutcome() + ")");
        }

        // Amount: invoice = (PI prices × GRN quantities) − validated credit, exact 2dp.
        if (currencyOk) {
            Money goodsValue = Money.zero(in.piCurrency());
            for (MatchInputs.PiLine line : in.piLines()) {
                int grnQty = in.grnQuantities().getOrDefault(line.skuId(), 0);
                goodsValue = goodsValue.plus(new UnitPrice(line.unitPriceAmount(), in.piCurrency()).multiply(grnQty));
            }
            Money expected = (in.claimedCredit() != null && in.creditValid())
                ? goodsValue.minus(in.claimedCredit())
                : goodsValue;
            if (!amountsEqual(in.invoiceAmount(), expected)) {
                failures.add("Amount: expected " + money(expected) + " but invoice is " + money(in.invoiceAmount()));
            }
        }

        return failures.isEmpty()
            ? new MatchVerdict(true, null)
            : new MatchVerdict(false, String.join("; ", failures));
    }

    private static boolean amountsEqual(Money a, Money b) {
        return a.currency().equals(b.currency()) && a.amount().compareTo(b.amount()) == 0;
    }

    private static String money(Money m) {
        return m.currency() + " " + m.amount().toPlainString();
    }

    /**
     * The legs, already gathered and tenant-resolved by the service. Quantities
     * are per SKU; the PI carries the confirmed prices the amount check uses.
     */
    record MatchInputs(
        Map<UUID, Integer> poQuantities,
        String piCurrency,
        List<PiLine> piLines,
        Map<UUID, Integer> grnQuantities,
        Money invoiceAmount,
        Money claimedCredit,
        boolean creditValid,
        String creditOutcome
    ) {
        Integer piQuantity(UUID skuId) {
            return piLines.stream().filter(l -> l.skuId().equals(skuId)).map(PiLine::quantity).findFirst().orElse(null);
        }

        record PiLine(UUID skuId, BigDecimal unitPriceAmount, int quantity) {
        }
    }

    /** The verdict — pass, or fail with the collected per-leg detail. */
    record MatchVerdict(boolean passed, String detail) {
    }
}
