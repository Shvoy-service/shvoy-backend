package com.shvoy.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

class InviteEmailComposerTest {

    private final InviteEmailComposer composer =
        new InviteEmailComposer(new FrontendLinks("http://localhost:5173"));

    @Test
    void namesInviterAndCompanyWithTheAcceptLinkAndExpiry() {
        EmailContent content = composer.compose(
            "Acme Ltd", "admin@acme.example", "raw-tok", Instant.parse("2026-08-27T10:15:30Z"));

        assertThat(content.subject()).isEqualTo("SHVOY invite");
        assertThat(content.body())
            .contains("admin@acme.example has invited you to join Acme Ltd on SHVOY.")
            .contains("http://localhost:5173/invite/accept?token=raw-tok")
            .contains("expires on 2026-08-27 (UTC)");
    }

    @Test
    void keepsTheRawTokenOnlyInsideTheLink() {
        EmailContent content = composer.compose(
            "Acme Ltd", "admin@acme.example", "SECRET-TOKEN", Instant.parse("2026-08-27T00:00:00Z"));

        String body = content.body();
        int firstOccurrence = body.indexOf("SECRET-TOKEN");
        // The only occurrence of the token is the one immediately after "token=".
        assertThat(firstOccurrence).isPositive();
        assertThat(body.indexOf("SECRET-TOKEN", firstOccurrence + 1)).isEqualTo(-1);
        assertThat(body).contains("token=SECRET-TOKEN");
    }

    @Test
    void dropsTheInviterAttributionWhenUnknown() {
        EmailContent content = composer.compose(
            "Acme Ltd", null, "raw-tok", Instant.parse("2026-08-27T00:00:00Z"));

        assertThat(content.body())
            .startsWith("You've been invited to join Acme Ltd on SHVOY.")
            .doesNotContain("has invited you");
    }
}
