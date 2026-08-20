package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class FrontendLinksTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void buildsEachRouteFromTheConfiguredBase() {
        FrontendLinks links = new FrontendLinks("http://localhost:5173");

        assertThat(links.inviteAccept("tok-123")).isEqualTo("http://localhost:5173/invite/accept?token=tok-123");
        assertThat(links.purchaseOrder(ID)).isEqualTo("http://localhost:5173/purchase-orders/" + ID);
        assertThat(links.reconciliation(ID)).isEqualTo("http://localhost:5173/reconciliation/" + ID);
        assertThat(links.discrepancyCase(ID)).isEqualTo("http://localhost:5173/discrepancies/" + ID);
    }

    @Test
    void normalisesASingleTrailingSlashOnTheBase() {
        FrontendLinks links = new FrontendLinks("https://app.example.com/");

        assertThat(links.purchaseOrder(ID)).isEqualTo("https://app.example.com/purchase-orders/" + ID);
    }

    @Test
    void encodesUnsafeTokenCharactersButLeavesTheRealTokenCharsetVerbatim() {
        FrontendLinks links = new FrontendLinks("http://localhost:5173");

        // A stray space would break the query — it must be encoded.
        assertThat(links.inviteAccept("a b")).isEqualTo("http://localhost:5173/invite/accept?token=a+b");
        // The real token is unpadded URL-safe Base64 ([A-Za-z0-9_-]) — it passes through unchanged.
        assertThat(links.inviteAccept("Ab9-_x")).isEqualTo("http://localhost:5173/invite/accept?token=Ab9-_x");
    }
}
