package com.shvoy.suppliers.domain;

import com.shvoy.Money;

/**
 * The result of applying PaymentTerms' deposit/balance split to an order
 * total — see PaymentTerms#split. {@code deposit.plus(balance)} always
 * equals the original total exactly; see that method's Javadoc for why.
 */
public record PaymentSplit(Money deposit, Money balance) {
}
