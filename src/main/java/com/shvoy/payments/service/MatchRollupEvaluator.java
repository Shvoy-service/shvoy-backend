package com.shvoy.payments.service;

import com.shvoy.Money;

/**
 * The PO-level rollup (Story 6.5 re-spec) — one isolated evaluator, run after
 * an invoice passes its own strategy but before the verdict commits. Per-invoice
 * matching created a failure mode the 1:1 world couldn't have: invoices that are
 * each individually plausible yet <strong>collectively over-claim</strong>. The
 * rollup is the sanity bound over the running position.
 *
 * <p>An invoice fails the rollup if letting it through would push the cumulative
 * matched invoice value past <em>either</em>:
 * <ul>
 *   <li>the cumulative received value plus the deposit obligation (you can't
 *       have matched more than has arrived, plus the up-front deposit that
 *       precedes shipment by design), or</li>
 *   <li>the PO's own coverage (its ordered value) — the over-invoice bound,
 *       which was warn-and-surface at entry (6.4) and becomes a <strong>hard
 *       fail</strong> here.</li>
 * </ul>
 * Pure and Spring-free, like the strategy evaluator.
 */
final class MatchRollupEvaluator {

    private MatchRollupEvaluator() {
    }

    /**
     * @param matchedIncludingThis cumulative matched value if this invoice is allowed through
     * @param receivedValue        cumulative received value (PI-priced)
     * @param depositObligation    the PO's snapshotted deposit amount (zero if none)
     * @param poCoverage           the PO's ordered value
     * @return null if the rollup is satisfied, else the failure detail
     */
    static String check(Money matchedIncludingThis, Money receivedValue, Money depositObligation, Money poCoverage) {
        Money receiptBound = receivedValue.plus(depositObligation);
        if (matchedIncludingThis.amount().compareTo(receiptBound.amount()) > 0) {
            return "Rollup: cumulative matched " + money(matchedIncludingThis) + " exceeds received value + deposit "
                + money(receiptBound);
        }
        if (matchedIncludingThis.amount().compareTo(poCoverage.amount()) > 0) {
            return "Rollup: cumulative matched " + money(matchedIncludingThis) + " exceeds PO coverage "
                + money(poCoverage) + " (over-invoiced)";
        }
        return null;
    }

    private static String money(Money m) {
        return m.currency() + " " + m.amount().toPlainString();
    }
}
