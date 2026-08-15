package com.shvoy.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import com.shvoy.payments.domain.CreditLedgerAuditEvent;
import com.shvoy.payments.domain.CreditLedgerEntry;

/**
 * Story 6.7's immutability, verified structurally. Amount and cause can't be
 * changed after creation (no setters — a correction is cancel-and-relog), and
 * the audit trail is genuinely append-only (no delete/update path) — the two
 * properties the ledger's "only accepted if it matches an open entry" control
 * depends on.
 */
class CreditLedgerImmutabilityTest {

    @Test
    void theCreditEntryHasNoSetters() {
        for (Method method : CreditLedgerEntry.class.getDeclaredMethods()) {
            assertThat(method.getName())
                .as("credit entry amount/cause are immutable — no setter %s", method.getName())
                .doesNotStartWith("set");
        }
    }

    @Test
    void theAuditEventEntityHasNoSetters() {
        for (Method method : CreditLedgerAuditEvent.class.getDeclaredMethods()) {
            assertThat(method.getName()).doesNotStartWith("set");
        }
    }

    @Test
    void theAuditRepositoryExposesNoDeleteOrUpdatePath() {
        assertThat(CrudRepository.class.isAssignableFrom(CreditLedgerAuditEventRepository.class))
            .as("audit repository must not extend CrudRepository (which brings delete methods)")
            .isFalse();
        for (Method method : CreditLedgerAuditEventRepository.class.getMethods()) {
            assertThat(method.getName())
                .as("audit repository must expose no delete/remove path — found %s", method.getName())
                .doesNotContain("delete")
                .doesNotContain("remove");
        }
    }
}
