package com.shvoy.suppliers.domain;

/**
 * A supplier's compliance state (supplier remodel) — a simple MVP flag; the
 * category→required-certificates engine is a later story. {@code CONFIRMED} is
 * one of the fields "ready for validation" derives from.
 */
public enum ComplianceStatus {
    PENDING,
    CONFIRMED
}
