package com.shvoy.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

class ApprovalRequestEmailComposerTest {

    private static final UUID PI_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ApprovalRequestEmailComposer composer =
        new ApprovalRequestEmailComposer(new FrontendLinks("http://localhost:5173"));

    @Test
    void spellsOutThePriceIncreaseSignOffRequirementAndLinksTheReconciliation() {
        EmailContent content = composer.compose("PO-0042", "Shenzhen Widgets Co", true, 2, PI_ID);

        assertThat(content.subject()).isEqualTo("Approval needed — PI variance on PO-0042");
        assertThat(content.body())
            .contains("PO-0042")
            .contains("Shenzhen Widgets Co")
            .contains("unit-price increase beyond tolerance")
            .contains("2 distinct approver sign-offs")
            .contains("http://localhost:5173/reconciliation/" + PI_ID);
    }

    @Test
    void framesTheSingleApproverPathWithoutClaimingAPriceIncrease() {
        EmailContent content = composer.compose("PO-0042", "Shenzhen Widgets Co", false, 2, PI_ID);

        assertThat(content.body())
            .contains("variance outside tolerance")
            .contains("single approver")
            .doesNotContain("price increase");
    }
}
