package com.shvoy.suppliers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.Money;

class PaymentTermsTest {

    @Test
    void splitOfAnOddTotalReconcilesExactlyWithTheRemainderOnTheBalance() {
        // A 30/70 split of 100.01 doesn't divide cleanly: 30% of 100.01 is
        // 30.003, which rounds down to 30.00 — the balance absorbs the extra cent.
        PaymentTerms terms = depositBalance("30");
        Money total = new Money(new BigDecimal("100.01"), "USD");

        PaymentSplit split = terms.split(total);

        assertThat(split.deposit().amount()).isEqualByComparingTo("30.00");
        assertThat(split.balance().amount()).isEqualByComparingTo("70.01");
        assertThat(split.deposit().plus(split.balance())).isEqualTo(total);
    }

    @Test
    void splitRoundsTheDepositHalfEvenNotHalfUp() {
        // 50% of 7.05 is exactly 3.525 — a tie; HALF_EVEN picks 3.52 (even).
        PaymentTerms terms = depositBalance("50");
        Money total = new Money(new BigDecimal("7.05"), "USD");

        PaymentSplit split = terms.split(total);

        assertThat(split.deposit().amount()).isEqualByComparingTo("3.52");
        assertThat(split.balance().amount()).isEqualByComparingTo("3.53");
        assertThat(split.deposit().plus(split.balance())).isEqualTo(total);
    }

    @Test
    void aNullDepositMeansZeroDepositAndAFullBalance() {
        // ZERO_DEPOSIT / ROLLING carry a null deposit_pct.
        PaymentTerms terms = new PaymentTerms(
            UUID.randomUUID(), PaymentTermsType.ZERO_DEPOSIT, null, AnchorEvent.BL, 30);
        Money total = new Money(new BigDecimal("100.00"), "USD");

        PaymentSplit split = terms.split(total);

        assertThat(split.deposit().amount()).isEqualByComparingTo("0.00");
        assertThat(split.balance().amount()).isEqualByComparingTo("100.00");
    }

    private static PaymentTerms depositBalance(String depositPct) {
        return new PaymentTerms(
            UUID.randomUUID(), PaymentTermsType.DEPOSIT_BALANCE, new BigDecimal(depositPct), AnchorEvent.BL, 30);
    }
}
