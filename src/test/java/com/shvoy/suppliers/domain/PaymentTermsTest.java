package com.shvoy.suppliers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.Money;

class PaymentTermsTest {

    @Test
    void balancePercentageIsDerivedAsOneHundredMinusDeposit() {
        PaymentTerms terms = new PaymentTerms(UUID.randomUUID(), new BigDecimal("33.5"), AnchorEvent.BL, 30);

        assertThat(terms.getBalancePercentage()).isEqualByComparingTo("66.5");
    }

    @Test
    void splitOfAnOddTotalReconcilesExactlyWithTheRemainderOnTheBalance() {
        // A 30/70 split of 100.01 doesn't divide cleanly: 30% of 100.01 is
        // 30.003, which rounds down to 30.00 — the balance absorbs the
        // extra cent rather than it being lost or double-counted.
        PaymentTerms terms = new PaymentTerms(UUID.randomUUID(), new BigDecimal("30"), AnchorEvent.BL, 30);
        Money total = new Money(new BigDecimal("100.01"), "USD");

        PaymentSplit split = terms.split(total);

        assertThat(split.deposit().amount()).isEqualByComparingTo("30.00");
        assertThat(split.balance().amount()).isEqualByComparingTo("70.01");
        assertThat(split.deposit().plus(split.balance())).isEqualTo(total);
    }

    @Test
    void splitRoundsTheDepositHalfEvenNotHalfUp() {
        // 50% of 7.05 is exactly 3.525 — a tie between 3.52 and 3.53.
        // HALF_EVEN (the codebase's one rounding rule, see MoneyTest) picks
        // 3.52 (even); HALF_UP would have picked 3.53.
        PaymentTerms terms = new PaymentTerms(UUID.randomUUID(), new BigDecimal("50"), AnchorEvent.INVOICE, 0);
        Money total = new Money(new BigDecimal("7.05"), "USD");

        PaymentSplit split = terms.split(total);

        assertThat(split.deposit().amount()).isEqualByComparingTo("3.52");
        assertThat(split.balance().amount()).isEqualByComparingTo("3.53");
        assertThat(split.deposit().plus(split.balance())).isEqualTo(total);
    }
}
