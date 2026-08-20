package com.shvoy.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

class DiscrepancyEmailComposerTest {

    private static final UUID CASE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final DiscrepancyEmailComposer composer =
        new DiscrepancyEmailComposer(new FrontendLinks("http://localhost:5173"));

    @Test
    void summarisesTheBlockAndLinksTheCase() {
        EmailContent content = composer.compose(
            "PO-0042", "Invoice unit price 12.50 exceeds PO 10.00 on line 2", CASE_ID);

        assertThat(content.subject()).isEqualTo("Payment discrepancy — PO-0042");
        assertThat(content.body())
            .contains("blocked the payment for purchase order PO-0042")
            .contains("What mismatched: Invoice unit price 12.50 exceeds PO 10.00 on line 2")
            .contains("http://localhost:5173/discrepancies/" + CASE_ID);
    }
}
