package com.shvoy.purchaseorders.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Issues per-company sequential PO references ("PO-0001", "PO-0002", ...) —
 * see Story 4.1. Two different companies each having their own "PO-0001"
 * is correct, not a collision (see V18's unique index, scoped by
 * company_id).
 *
 * {@link #ensureCounterRowExists} and {@link PoNumberCounterClaimer}'s
 * transactional lock-and-increment run sequentially, never nested — that's
 * deliberate. An earlier version wrapped the row-creation step in its own
 * REQUIRES_NEW transaction (to stop a lost race there from poisoning the
 * claim's transaction — Postgres aborts an entire transaction after any
 * failed statement, so catching a DataIntegrityViolationException and
 * continuing in the *same* transaction isn't safe against real Postgres
 * even though it happens to work under H2). REQUIRES_NEW suspends the
 * caller's connection rather than releasing it back to the pool, though —
 * so every concurrent caller needed two connections held at once, and 10
 * concurrent claims deadlocked a 10-connection pool outright (confirmed
 * the hard way running this story's own concurrency test). Running the two
 * steps sequentially instead means each caller only ever holds one
 * connection at a time, same as everywhere else in this codebase.
 */
@Service
public class PoNumberGenerator {

    private final JdbcTemplate jdbcTemplate;
    private final PoNumberCounterClaimer counterClaimer;

    PoNumberGenerator(JdbcTemplate jdbcTemplate, PoNumberCounterClaimer counterClaimer) {
        this.jdbcTemplate = jdbcTemplate;
        this.counterClaimer = counterClaimer;
    }

    public String claimNext(UUID companyId) {
        ensureCounterRowExists(companyId);
        return format(counterClaimer.claimFromExistingRow(companyId));
    }

    /**
     * Deliberately not @Transactional: a single INSERT is already atomic
     * via autocommit, and running it outside any Spring-managed
     * transaction means it only ever borrows a pooled connection for the
     * instant this one statement runs, not for this whole method's
     * duration — see the class Javadoc for why that matters.
     */
    private void ensureCounterRowExists(UUID companyId) {
        try {
            jdbcTemplate.update("INSERT INTO po_number_counters (company_id, last_number) VALUES (?, 0)", companyId);
        } catch (DataIntegrityViolationException e) {
            // Already exists — from an earlier claim, or a concurrent
            // claim whose insert committed first. Either way, exactly the
            // state this method promises by the time it returns.
        }
    }

    private static String format(int number) {
        return "PO-%04d".formatted(number);
    }
}
