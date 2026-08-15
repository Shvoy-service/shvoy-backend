package com.shvoy.suppliers.dto;

import org.springframework.modulith.NamedInterface;

import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * The bit of a supplier's payment terms the payments module needs to schedule
 * a balance's due date (Story 6.2): which event the due date is anchored to,
 * and the signed days offset from it. Deliberately narrow — the deposit
 * percentage / split already flow through {@code PaymentTermsService#trySplit};
 * this is only the anchor timing.
 *
 * {@code daysOffset} is signed — negative means "due N days before the anchor".
 */
@NamedInterface("payment-terms")
public record PaymentScheduleTerms(
    AnchorEvent anchorEvent,
    int daysOffset
) {
}
