package com.shvoy.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.Money;
import com.shvoy.payments.domain.InvoiceCoversType;
import com.shvoy.payments.service.InvoiceMatchEvaluator.Claim;
import com.shvoy.payments.service.InvoiceMatchEvaluator.Legs;
import com.shvoy.payments.service.InvoiceMatchEvaluator.Verdict;

/**
 * The per-{@code covers_type} match strategy (Story 6.5 re-spec), unit-tested
 * pure — each strategy's clean pass and its characteristic fail, plus the
 * currency/credit guards shared across all of them.
 */
class InvoiceMatchEvaluatorTest {

    private static final UUID SKU = UUID.randomUUID();

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), "USD");
    }

    /** Legs with 10 ordered @ 2.00, and {@code grnQty} received. */
    private static Legs legs(int grnQty, Money deposit, Money balance) {
        Money received = usd("2.00").multiply(BigDecimal.valueOf(grnQty));
        return new Legs("USD", Map.of(SKU, 10), Map.of(SKU, new BigDecimal("2.0000")), Map.of(SKU, 10),
            Map.of(SKU, grnQty), received, deposit, balance);
    }

    private static Claim claim(InvoiceCoversType covers, Money amount, Map<UUID, Integer> shipmentGrn,
            boolean shipmentReceipted, Map<UUID, Integer> claimedLines) {
        return new Claim(covers, amount, null, true, null, shipmentGrn, shipmentReceipted, claimedLines);
    }

    // --- DEPOSIT: matches the snapshot deposit obligation, no GRN required (deposits precede shipment) ---

    @Test
    void depositMatchesPreShipmentWithNoReceipt() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(0, usd("6.00"), usd("14.00")),
            claim(InvoiceCoversType.DEPOSIT, usd("6.00"), Map.of(), false, Map.of()), Money.zero("USD"));
        assertThat(v.passed()).isTrue();
        assertThat(v.expected()).isEqualTo(usd("6.00"));
    }

    @Test
    void depositAPennyOffFails() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(0, usd("6.00"), usd("14.00")),
            claim(InvoiceCoversType.DEPOSIT, usd("6.01"), Map.of(), false, Map.of()), Money.zero("USD"));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("expected USD 6.00");
    }

    // --- BALANCE: the snapshot balance amount, but only once received-complete (cumulative) ---

    @Test
    void balanceMatchesWhenReceivedComplete() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")),
            claim(InvoiceCoversType.BALANCE, usd("20.00"), Map.of(), false, Map.of()), Money.zero("USD"));
        assertThat(v.passed()).isTrue();
    }

    @Test
    void balanceOnAHalfShippedPoFailsReceiptIncomplete() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(8, null, usd("20.00")),
            claim(InvoiceCoversType.BALANCE, usd("20.00"), Map.of(), false, Map.of()), Money.zero("USD"));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("Receipt incomplete");
    }

    // --- SHIPMENT: the referenced GRN's received qty × PI prices (strongest strategy) ---

    @Test
    void shipmentPricesItsReceivedQuantitiesAtPiPrices() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")),
            claim(InvoiceCoversType.SHIPMENT, usd("20.00"), Map.of(SKU, 10), true, Map.of()), Money.zero("USD"));
        assertThat(v.passed()).isTrue();
    }

    @Test
    void shipmentAPennyOverFails() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")),
            claim(InvoiceCoversType.SHIPMENT, usd("20.01"), Map.of(SKU, 10), true, Map.of()), Money.zero("USD"));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("expected USD 20.00");
    }

    // --- LINES: claimed qty × PI prices, capped at cumulative receipt of those lines ---

    @Test
    void linesMatchWithinReceipt() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")),
            claim(InvoiceCoversType.LINES, usd("10.00"), Map.of(), false, Map.of(SKU, 5)), Money.zero("USD"));
        assertThat(v.passed()).isTrue();
    }

    @Test
    void linesExceedingLineReceiptFails() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")),
            claim(InvoiceCoversType.LINES, usd("24.00"), Map.of(), false, Map.of(SKU, 12)), Money.zero("USD"));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("only 10 received");
    }

    // --- AMOUNT: the fallback — fits within unclaimed received value, always flagged position-matched ---

    @Test
    void amountWithinUnclaimedReceivedValuePassesFlagged() {
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")),
            claim(InvoiceCoversType.AMOUNT, usd("15.00"), Map.of(), false, Map.of()), Money.zero("USD"));
        assertThat(v.passed()).isTrue();
        assertThat(v.positionMatched()).isTrue();
    }

    @Test
    void amountBeyondUnclaimedReceivedValueFailsButStaysFlagged() {
        // 15.00 already matched leaves only 5.00 of the 20.00 received value unclaimed.
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")),
            claim(InvoiceCoversType.AMOUNT, usd("10.00"), Map.of(), false, Map.of()), usd("15.00"));
        assertThat(v.passed()).isFalse();
        assertThat(v.positionMatched()).isTrue();
        assertThat(v.detail()).contains("unclaimed");
    }

    // --- shared guards ---

    @Test
    void aDifferentCurrencyAlwaysFails() {
        Claim gbp = new Claim(InvoiceCoversType.BALANCE, new Money(new BigDecimal("20.00"), "GBP"),
            null, true, null, Map.of(), false, Map.of());
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")), gbp, Money.zero("USD"));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("Currency");
    }

    @Test
    void anUnagreedClaimedCreditFails() {
        Claim c = new Claim(InvoiceCoversType.BALANCE, usd("15.00"), usd("5.00"), false, "NO_OPEN_CREDIT",
            Map.of(), false, Map.of());
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")), c, Money.zero("USD"));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("Claimed credit");
    }

    @Test
    void anAgreedCreditReducesTheExpectedAmount() {
        Claim c = new Claim(InvoiceCoversType.BALANCE, usd("15.00"), usd("5.00"), true, null,
            Map.of(), false, Map.of());
        Verdict v = InvoiceMatchEvaluator.evaluate(legs(10, null, usd("20.00")), c, Money.zero("USD"));
        assertThat(v.passed()).isTrue(); // expected 20.00 − 5.00 = 15.00
    }
}
