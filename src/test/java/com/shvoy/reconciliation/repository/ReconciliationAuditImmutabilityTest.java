package com.shvoy.reconciliation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import com.shvoy.reconciliation.domain.ReconciliationAuditEvent;

/**
 * Story 5.7's immutability requirement, verified <em>structurally</em>: the
 * audit trail is append-only because there is genuinely no code path to alter
 * or remove an entry — not because we merely chose not to call one. If someone
 * later adds a setter or a delete method, this test fails.
 */
class ReconciliationAuditImmutabilityTest {

    @Test
    void theAuditEventEntityHasNoMutators() {
        for (Method method : ReconciliationAuditEvent.class.getDeclaredMethods()) {
            assertThat(method.getName())
                .as("audit events must be construct-only — no setter/mutator %s", method.getName())
                .doesNotStartWith("set");
        }
    }

    @Test
    void theAuditRepositoryExposesNoUpdateOrDeletePath() {
        // Not a CrudRepository/JpaRepository — so it never inherits deleteAll/deleteById/etc.
        assertThat(CrudRepository.class.isAssignableFrom(ReconciliationAuditEventRepository.class))
            .as("audit repository must not extend CrudRepository (which brings delete methods)")
            .isFalse();

        for (Method method : ReconciliationAuditEventRepository.class.getMethods()) {
            assertThat(method.getName())
                .as("audit repository must expose no delete/remove path — found %s", method.getName())
                .doesNotContain("delete")
                .doesNotContain("remove");
        }
    }
}
