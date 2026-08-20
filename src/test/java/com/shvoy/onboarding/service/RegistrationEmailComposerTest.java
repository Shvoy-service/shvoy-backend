package com.shvoy.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

class RegistrationEmailComposerTest {

    private final RegistrationEmailComposer composer =
        new RegistrationEmailComposer(new FrontendLinks("http://localhost:5173"));

    @Test
    void namesTheCompanyWithTheSetPasswordLinkAndExpiry() {
        EmailContent content = composer.compose(
            "Acme Ltd", "raw-tok", Instant.parse("2026-08-21T10:15:30Z"));

        assertThat(content.subject()).isEqualTo("Activate your SHVOY account");
        assertThat(content.body())
            .contains("You registered Acme Ltd on SHVOY.")
            .contains("http://localhost:5173/set-password?token=raw-tok")
            .contains("expires on 2026-08-21 (UTC)")
            .contains("If you didn't register");
    }

    @Test
    void keepsTheRawTokenOnlyInsideTheLink() {
        EmailContent content = composer.compose(
            "Acme Ltd", "SECRET-TOKEN", Instant.parse("2026-08-21T00:00:00Z"));

        String body = content.body();
        int firstOccurrence = body.indexOf("SECRET-TOKEN");
        // The only occurrence of the token is the one immediately after "token=".
        assertThat(firstOccurrence).isPositive();
        assertThat(body.indexOf("SECRET-TOKEN", firstOccurrence + 1)).isEqualTo(-1);
        assertThat(body).contains("token=SECRET-TOKEN");
    }
}
