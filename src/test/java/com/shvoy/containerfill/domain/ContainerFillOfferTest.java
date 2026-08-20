package com.shvoy.containerfill.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ContainerFillOfferTest {

    private ContainerFillOffer newOffer() {
        return new ContainerFillOffer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("2.50"), "notes", UUID.randomUUID());
    }

    @Test
    void aFreshOfferIsOpenUndecidedAndCancellable() {
        ContainerFillOffer offer = newOffer();
        assertThat(offer.getStatus()).isEqualTo(ContainerFillOfferStatus.OPEN);
        assertThat(offer.isUndecided()).isTrue();
        assertThat(offer.isCancellable()).isTrue();
        assertThat(offer.getUpdatedAt()).isNull();
    }

    @Test
    void cancellingMovesItToCancelledAndOutOfTheUndecidedQueue() {
        ContainerFillOffer offer = newOffer();
        offer.cancel();
        assertThat(offer.getStatus()).isEqualTo(ContainerFillOfferStatus.CANCELLED);
        assertThat(offer.isUndecided()).isFalse();
        assertThat(offer.isCancellable()).isFalse();
        assertThat(offer.getUpdatedAt()).isNotNull();
    }
}
