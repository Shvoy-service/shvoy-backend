package com.shvoy.suppliers.domain;

import org.springframework.modulith.NamedInterface;

import com.shvoy.Money;

/**
 * The result of applying PaymentTerms' deposit/balance split to an order
 * total — see PaymentTerms#split. {@code deposit.plus(balance)} always
 * equals the original total exactly; see that method's Javadoc for why.
 *
 * Exposed as its own named interface (Story 4.3) so other modules can
 * depend on this result directly, via {@code PaymentTermsService#trySplit}
 * — never {@code PaymentTerms}/{@code PaymentTermsRepository} themselves,
 * which stay internal to this module. Same pattern as
 * {@code PriceResolutionResult} (3.8).
 */
@NamedInterface("payment-terms")
public record PaymentSplit(Money deposit, Money balance) {
}
