package com.shvoy.onboarding.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

/**
 * The invite email's wording (Story 9.5) — who invited you, to which company,
 * the accept link, and when it expires. Owns the content; {@code
 * InvitationService} owns the recipient and the token's lifecycle.
 *
 * <p>This is the email that made the token-hygiene rule: the raw token appears
 * <em>only</em> inside the link (built by {@link FrontendLinks}), never
 * elsewhere in the body and never in the persisted send record.
 */
@Component
class InviteEmailComposer {

    private final FrontendLinks frontendLinks;

    InviteEmailComposer(FrontendLinks frontendLinks) {
        this.frontendLinks = frontendLinks;
    }

    /**
     * @param companyName  the company the invitee is being invited to join
     * @param inviterEmail the person who sent the invite (nullable — the wording drops the attribution if unknown)
     * @param rawToken     the raw invite token; goes only into the link
     * @param expiresAt    when the link lapses
     */
    EmailContent compose(String companyName, String inviterEmail, String rawToken, Instant expiresAt) {
        String acceptLink = frontendLinks.inviteAccept(rawToken);
        String expiryDate = DateTimeFormatter.ISO_LOCAL_DATE.format(expiresAt.atZone(ZoneOffset.UTC));

        String opening = inviterEmail == null || inviterEmail.isBlank()
            ? "You've been invited to join " + companyName + " on SHVOY."
            : inviterEmail + " has invited you to join " + companyName + " on SHVOY.";

        String body = opening + "\n\n"
            + "Accept your invitation:\n"
            + acceptLink + "\n\n"
            + "This link expires on " + expiryDate + " (UTC). "
            + "If it lapses, ask an administrator at " + companyName + " to send a fresh invite.\n\n"
            + "— SHVOY";

        return new EmailContent("SHVOY invite", body);
    }
}
