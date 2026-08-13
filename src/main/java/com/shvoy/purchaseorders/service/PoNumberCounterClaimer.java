package com.shvoy.purchaseorders.service;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of {@link PoNumberGenerator}'s claim — locks and
 * increments an existing {@code po_number_counters} row (the row must
 * already exist; see {@code PoNumberGenerator#ensureCounterRowExists}). A
 * separate bean/transaction, not a private method on PoNumberGenerator,
 * for two reasons: {@code @Transactional} only takes effect through
 * Spring's proxy — a self-invoked private method would silently run with
 * no transaction at all — and keeping it a genuinely separate call (not
 * nested inside another transaction) is what keeps this to one connection
 * per caller; see PoNumberGenerator's Javadoc.
 */
@Service
class PoNumberCounterClaimer {

    private final JdbcTemplate jdbcTemplate;

    PoNumberCounterClaimer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    int claimFromExistingRow(UUID companyId) {
        int current = jdbcTemplate.queryForObject(
            "SELECT last_number FROM po_number_counters WHERE company_id = ? FOR UPDATE", Integer.class, companyId);
        int next = current + 1;
        jdbcTemplate.update("UPDATE po_number_counters SET last_number = ? WHERE company_id = ?", next, companyId);
        return next;
    }
}
