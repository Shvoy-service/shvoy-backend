package com.shvoy.onboarding.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

/**
 * The self-registration verification email's wording — the activation link and
 * when it expires. Owns the content; {@code RegistrationService} owns the
 * recipient and the token's lifecycle. Same token-hygiene rule as
 * {@link InviteEmailComposer}: the raw token appears <em>only</em> inside the
 * link (built by {@link FrontendLinks}), never elsewhere in the body and never
 * in the persisted send record.
 */
@Component
class RegistrationEmailComposer {

    private final FrontendLinks frontendLinks;

    RegistrationEmailComposer(FrontendLinks frontendLinks) {
        this.frontendLinks = frontendLinks;
    }

    /**
     * @param companyName the company the registrant just created
     * @param rawToken    the raw verification token; goes only into the link
     * @param expiresAt   when the link lapses
     */
    EmailContent compose(String companyName, String rawToken, Instant expiresAt) {
        String activationLink = frontendLinks.setPassword(rawToken);
        String expiryDate = DateTimeFormatter.ISO_LOCAL_DATE.format(expiresAt.atZone(ZoneOffset.UTC));

        // No "if it lapses, ..." advice, unlike the invite email: an expired
        // registration token has no self-service recovery today (the PENDING
        // row makes re-registering the same email a DUPLICATE_EMAIL conflict),
        // so any instruction here would be wrong. Add one when that flow exists.
        String body = "You registered " + companyName + " on SHVOY.\n\n"
            + "Set your password to activate your account:\n"
            + activationLink + "\n\n"
            + "This link expires on " + expiryDate + " (UTC).\n\n"
            + "If you didn't register, you can ignore this email — no account is active until the link is used.\n\n"
            + "— SHVOY";

        return new EmailContent("Activate your SHVOY account", body);
    }
}
