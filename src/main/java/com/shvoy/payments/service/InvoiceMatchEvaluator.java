package com.shvoy.payments.service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.UnitPrice;
import com.shvoy.payments.domain.InvoiceCoversType;

/**
 * The per-invoice match rule (Story 6.5 re-spec), pure and Spring-free so every
 * strategy is exhaustively unit-testable — same discipline as {@code
 * VarianceCalculator} / {@code ToleranceEvaluator}. Each invoice is matched
 * against <strong>what it declares it covers</strong>: the {@code covers_type}
 * selects the strategy, which defines the expected amount and the legs it needs.
 * The <em>consequence</em> of a verdict (payment gating vs statement-feeding) is
 * a separate dispatch on {@code terms_type} — {@link MatchConsequencePolicy} —
 * kept deliberately apart from "what matches" so the two compose freely.
 *
 * <p><strong>Money equality is exact</strong> (2dp, no tolerance) for every
 * strategy except {@code AMOUNT}: the invoice is the final ask and either equals
 * the agreed position or routes to a human. {@code AMOUNT} is the fallback — no
 * declared coverage, so it can only be <em>position-matched</em> (it must fit
 * within the still-unclaimed received value), and a pass there is flagged
 * {@code positionMatched} so Finance sees it reconciled loosely.
 *
 * <p><strong>Reference basis (b):</strong> {@code SHIPMENT}/{@code LINES}
 * expected = confirmed-PI unit prices × the <em>received</em> quantities, minus
 * any validated credit — so the GRN leg means something (catches short-shipped-
 * but-fully-invoiced). {@code DEPOSIT}/{@code BALANCE} check the snapshotted
 * obligation amounts (6.1). See docs/CONTRACT.md.
 *
 * <p>The rollup (collective over-claim across invoices) is a separate isolated
 * check, {@link MatchRollupEvaluator}, applied by the service after a strategy
 * passes — an individually-plausible invoice can still fail there.
 */
final class InvoiceMatchEvaluator {

    private InvoiceMatchEvaluator() {
    }

    /** The gathered legs, per PO — built once by the service, shared across every invoice on the PO. */
    record Legs(
        String piCurrency,
        Map<UUID, Integer> poQuantities,
        Map<UUID, BigDecimal> piPrices,
        Map<UUID, Integer> piQuantities,
        Map<UUID, Integer> cumulativeGrn,
        Money receivedValue,
        Money depositAmount,
        Money balanceAmount
    ) {
    }

    /** One invoice's claim, with its credit already resolved (existence/ownership) by the service. */
    record Claim(
        InvoiceCoversType coversType,
        Money invoiceAmount,
        Money claimedCredit,
        boolean creditValid,
        String creditOutcome,
        Map<UUID, Integer> shipmentGrn,
        boolean shipmentConsignmentReceipted,
        Map<UUID, Integer> claimedLines
    ) {
    }

    /** The per-invoice verdict — pass/fail, the flag for a loose AMOUNT position-match, and the expected amount (null for AMOUNT). */
    record Verdict(boolean passed, boolean positionMatched, Money expected, String detail) {
    }

    /**
     * @param alreadyMatched cumulative value of prior <em>passed</em> invoices on this PO — the
     *                       AMOUNT strategy's "how much received value is still unclaimed" basis.
     */
    static Verdict evaluate(Legs legs, Claim claim, Money alreadyMatched) {
        if (!legs.piCurrency().equals(claim.invoiceAmount().currency())) {
            return fail("Currency: PI is " + legs.piCurrency() + " but invoice is " + claim.invoiceAmount().currency());
        }
        if (claim.claimedCredit() != null && !claim.creditValid()) {
            return fail("Claimed credit " + money(claim.claimedCredit())
                + " does not match an open ledger entry (" + claim.creditOutcome() + ")");
        }
        return switch (claim.coversType()) {
            case DEPOSIT -> matchAgainst(claim, legs.depositAmount(), "deposit obligation");
            case BALANCE -> balance(legs, claim);
            case SHIPMENT -> shipment(legs, claim);
            case LINES -> lines(legs, claim);
            case AMOUNT -> amount(legs, claim, alreadyMatched);
        };
    }

    private static Verdict balance(Legs legs, Claim claim) {
        if (legs.balanceAmount() == null) {
            return fail("Balance: the PO carries no balance obligation");
        }
        // The balance claims everything — it matches only once the PO is received-complete (cumulative).
        for (Map.Entry<UUID, Integer> line : legs.poQuantities().entrySet()) {
            int received = legs.cumulativeGrn().getOrDefault(line.getKey(), 0);
            if (received < line.getValue()) {
                return fail("Receipt incomplete: SKU " + line.getKey() + " ordered " + line.getValue()
                    + " but only " + received + " received");
            }
        }
        return matchAmount(claim, applyCredit(legs.balanceAmount(), claim));
    }

    private static Verdict shipment(Legs legs, Claim claim) {
        if (!claim.shipmentConsignmentReceipted()) {
            return fail("Shipment: the referenced consignment is not receipted against this PO");
        }
        Money expected = applyCredit(valueOf(claim.shipmentGrn(), legs), claim);
        return matchAmount(claim, expected);
    }

    private static Verdict lines(Legs legs, Claim claim) {
        if (claim.claimedLines() == null || claim.claimedLines().isEmpty()) {
            return fail("Lines: no covered lines declared");
        }
        for (Map.Entry<UUID, Integer> line : claim.claimedLines().entrySet()) {
            int received = legs.cumulativeGrn().getOrDefault(line.getKey(), 0);
            if (line.getValue() > received) {
                return fail("Line SKU " + line.getKey() + " claims " + line.getValue()
                    + " but only " + received + " received");
            }
        }
        return matchAmount(claim, applyCredit(valueOf(claim.claimedLines(), legs), claim));
    }

    private static Verdict amount(Legs legs, Claim claim, Money alreadyMatched) {
        // No declared coverage: position-match — it may claim no more than the still-unclaimed received value.
        Money unclaimed = legs.receivedValue().minus(alreadyMatched);
        if (claim.invoiceAmount().amount().compareTo(unclaimed.amount()) > 0) {
            return new Verdict(false, true, null,
                "Position: claims " + money(claim.invoiceAmount()) + " but only " + money(unclaimed)
                    + " of received value is unclaimed");
        }
        return new Verdict(true, true, null, null);
    }

    /** DEPOSIT and the shared exact-amount path for a snapshotted obligation. */
    private static Verdict matchAgainst(Claim claim, Money obligation, String label) {
        if (obligation == null) {
            return fail("The PO carries no " + label);
        }
        return matchAmount(claim, applyCredit(obligation, claim));
    }

    private static Verdict matchAmount(Claim claim, Money expected) {
        if (!amountsEqual(claim.invoiceAmount(), expected)) {
            return fail("Amount: expected " + money(expected) + " but invoice is " + money(claim.invoiceAmount()));
        }
        return new Verdict(true, false, expected, null);
    }

    private static Money applyCredit(Money base, Claim claim) {
        return (claim.claimedCredit() != null && claim.creditValid()) ? base.minus(claim.claimedCredit()) : base;
    }

    private static Money valueOf(Map<UUID, Integer> quantities, Legs legs) {
        Money value = Money.zero(legs.piCurrency());
        Set<UUID> skus = new LinkedHashSet<>(quantities.keySet());
        for (UUID sku : skus) {
            BigDecimal price = legs.piPrices().get(sku);
            if (price == null) {
                continue; // a SKU with no PI price contributes nothing — the quantity chain / amount check surfaces it
            }
            value = value.plus(new UnitPrice(price, legs.piCurrency()).multiply(quantities.get(sku)));
        }
        return value;
    }

    private static boolean amountsEqual(Money a, Money b) {
        return a.currency().equals(b.currency()) && a.amount().compareTo(b.amount()) == 0;
    }

    private static Verdict fail(String detail) {
        return new Verdict(false, false, null, detail);
    }

    private static String money(Money m) {
        return m.currency() + " " + m.amount().toPlainString();
    }
}
