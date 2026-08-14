package com.shvoy.suppliers.domain;

/**
 * The event a supplier's payment due date is measured from — see
 * PaymentTerms. Feature 7 resolves the actual due date by adding the terms'
 * days offset to whichever one of these dates a real order has recorded;
 * this story only constrains which events are valid.
 */
public enum AnchorEvent {
    BL,
    INVOICE,
    ARRIVAL,
    EX_FACTORY
}
