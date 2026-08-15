package com.shvoy.payments.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.domain.PaymentType;

/**
 * One row of the payment queue (Story 6.3) — everything Screens 1/6 render for
 * a payment, joined across the PO and supplier so the frontend gets it in one
 * call: the PO reference, supplier name, type, amount, due date, and status.
 *
 * {@code overdue} and {@code awaitingDueDate} are <strong>derived at read
 * time</strong>, never stored:
 * <ul>
 *   <li>{@code overdue} — the due date is strictly before today and the
 *       payment isn't {@code PAID}. Due <em>today</em> is not overdue.</li>
 *   <li>{@code awaitingDueDate} — the payment has no due date yet (a balance
 *       whose anchor event hasn't occurred, 6.2); it's shown honestly, not
 *       given a fake date.</li>
 * </ul>
 */
public record PaymentQueueRow(
    UUID paymentId,
    UUID purchaseOrderId,
    String poReference,
    UUID supplierId,
    String supplierName,
    PaymentType type,
    Money amount,
    LocalDate dueDate,
    PaymentStatus status,
    boolean overdue,
    boolean awaitingDueDate
) {
}
