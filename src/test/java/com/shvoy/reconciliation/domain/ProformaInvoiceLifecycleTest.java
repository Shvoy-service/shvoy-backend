package com.shvoy.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.ConflictException;

/**
 * The status lifecycle in isolation (Story 5.7) — the permitted-transition
 * matrix and the entity guard that rejects illegal moves. Pure, no Spring.
 */
class ProformaInvoiceLifecycleTest {

    private ProformaInvoice newLoggedPi() {
        return new ProformaInvoice(UUID.randomUUID(), "SUP-REF", "USD", UUID.randomUUID());
    }

    @Test
    void theTransitionMatrixIsDefinedInOnePlace() {
        assertThat(ProformaInvoiceStatus.LOGGED.canTransitionTo(ProformaInvoiceStatus.AUTO_CONFIRMED)).isTrue();
        assertThat(ProformaInvoiceStatus.LOGGED.canTransitionTo(ProformaInvoiceStatus.ROUTED_FOR_APPROVAL)).isTrue();
        assertThat(ProformaInvoiceStatus.ROUTED_FOR_APPROVAL.canTransitionTo(ProformaInvoiceStatus.APPROVED)).isTrue();
        assertThat(ProformaInvoiceStatus.ROUTED_FOR_APPROVAL.canTransitionTo(ProformaInvoiceStatus.REJECTED)).isTrue();

        // The illegal ones the story calls out explicitly.
        assertThat(ProformaInvoiceStatus.AUTO_CONFIRMED.canTransitionTo(ProformaInvoiceStatus.ROUTED_FOR_APPROVAL)).isFalse();
        assertThat(ProformaInvoiceStatus.REJECTED.canTransitionTo(ProformaInvoiceStatus.APPROVED)).isFalse();
        assertThat(ProformaInvoiceStatus.APPROVED.canTransitionTo(ProformaInvoiceStatus.REJECTED)).isFalse();
        assertThat(ProformaInvoiceStatus.LOGGED.canTransitionTo(ProformaInvoiceStatus.APPROVED)).isFalse();

        // Any non-superseded state can be superseded; a same-state move is an idempotent no-op.
        assertThat(ProformaInvoiceStatus.APPROVED.canTransitionTo(ProformaInvoiceStatus.SUPERSEDED)).isTrue();
        assertThat(ProformaInvoiceStatus.SUPERSEDED.canTransitionTo(ProformaInvoiceStatus.APPROVED)).isFalse();
        assertThat(ProformaInvoiceStatus.ROUTED_FOR_APPROVAL.canTransitionTo(ProformaInvoiceStatus.ROUTED_FOR_APPROVAL)).isTrue();
    }

    @Test
    void aValidLifecycleSucceeds() {
        ProformaInvoice pi = newLoggedPi();
        pi.markRoutedForApproval();
        pi.markApproved();
        assertThat(pi.getStatus()).isEqualTo(ProformaInvoiceStatus.APPROVED);
    }

    @Test
    void anInvalidTransitionIsRejected() {
        ProformaInvoice pi = newLoggedPi();
        pi.markAutoConfirmed();
        assertThatThrownBy(pi::markRoutedForApproval)
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Illegal reconciliation status transition");
        assertThat(pi.getStatus()).isEqualTo(ProformaInvoiceStatus.AUTO_CONFIRMED); // unchanged
    }

    @Test
    void aRejectedPiCannotBecomeApproved() {
        ProformaInvoice pi = newLoggedPi();
        pi.markRoutedForApproval();
        pi.markRejected();
        assertThatThrownBy(pi::markApproved).isInstanceOf(ConflictException.class);
        assertThat(pi.getStatus()).isEqualTo(ProformaInvoiceStatus.REJECTED);
    }

    @Test
    void supersedingMovesToSupersededAndDeactivates() {
        ProformaInvoice pi = newLoggedPi();
        pi.markAutoConfirmed();
        pi.supersede();
        assertThat(pi.getStatus()).isEqualTo(ProformaInvoiceStatus.SUPERSEDED);
        assertThat(pi.isActive()).isFalse();
    }
}
