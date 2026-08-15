package com.shvoy.suppliers.dto;

/**
 * A supplier's <strong>full</strong> bank details (supplier remodel) — the
 * dedicated FINANCE/ADMIN-only read, never part of the default supplier response
 * (which shows a masked account number only).
 */
public record BankDetailsResponse(
    String accountName,
    String accountNumber,
    String sortCode
) {
}
