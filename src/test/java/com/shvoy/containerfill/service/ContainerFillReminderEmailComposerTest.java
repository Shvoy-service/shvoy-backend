package com.shvoy.containerfill.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

class ContainerFillReminderEmailComposerTest {

    private static final UUID OFFER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final ContainerFillReminderEmailComposer composer =
        new ContainerFillReminderEmailComposer(new FrontendLinks("http://localhost:5173"));

    @Test
    void rendersTheDeadlineInLondonUsingZoneRulesNotAFixedOffset() {
        // Clocks-back Sunday (2026-10-25): 17:00 London is already GMT → the instant is 17:00Z.
        EmailContent winter = composer.compose(new BigDecimal("2.50"), "Shenzhen Widgets Co", "BL-123",
            Instant.parse("2026-10-25T17:00:00Z"), OFFER_ID);
        assertThat(winter.subject()).isEqualTo("Container-fill decision due — Shenzhen Widgets Co (2.50 CBM)");
        assertThat(winter.body())
            .contains("17:00 (Europe/London)")
            .contains("BL-123")
            .contains("2.50 CBM")
            .contains("Shenzhen Widgets Co")
            .contains("http://localhost:5173/container-fill-offers/" + OFFER_ID);

        // Summer BST (+01:00): 16:00Z is 17:00 London — same wall-clock, proving DST-aware zone rules.
        EmailContent summer = composer.compose(new BigDecimal("2.50"), "Shenzhen Widgets Co", "BL-123",
            Instant.parse("2026-07-01T16:00:00Z"), OFFER_ID);
        assertThat(summer.body()).contains("17:00 (Europe/London)");
    }

    @Test
    void handlesAContainerWithNoBillOfLadingYet() {
        EmailContent content = composer.compose(new BigDecimal("1.00"), "Acme Ltd", null,
            Instant.parse("2026-07-01T16:00:00Z"), UUID.randomUUID());
        assertThat(content.body()).contains("bill of lading not yet issued");
    }
}
