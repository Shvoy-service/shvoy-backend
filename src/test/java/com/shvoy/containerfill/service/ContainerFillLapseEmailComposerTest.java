package com.shvoy.containerfill.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

class ContainerFillLapseEmailComposerTest {

    private static final UUID OFFER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final ContainerFillLapseEmailComposer composer =
        new ContainerFillLapseEmailComposer(new FrontendLinks("http://localhost:5173"));

    @Test
    void statesTheOfferLapsedAndLinksItForTheRecord() {
        EmailContent content = composer.compose(new BigDecimal("2.50"), "Shenzhen Widgets Co", "BL-123", OFFER_ID);

        assertThat(content.subject()).isEqualTo("Container-fill offer lapsed — Shenzhen Widgets Co (2.50 CBM)");
        assertThat(content.body())
            .contains("lapsed")
            .contains("shipped without")
            .contains("BL-123")
            .contains("2.50 CBM")
            .contains("Shenzhen Widgets Co")
            .contains("http://localhost:5173/container-fill-offers/" + OFFER_ID);
    }
}
