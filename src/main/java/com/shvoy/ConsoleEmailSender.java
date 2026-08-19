package com.shvoy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Temporary stand-in for real email delivery, until the Notifications
 * feature wires up SES — logs the message to the console instead of
 * sending it, exactly how {@code InvitationService} (2.3) already handled
 * its invite links before this interface existed. The single {@link
 * EmailSender} implementation for now: unlike {@code IdentityProvider},
 * the local/test stand-in for real delivery — dev/prod use {@code
 * SesEmailSender} (Story 9.4), selected by profile exactly like the identity
 * provider.
 *
 * The body is logged in full — deliberate, matching 2.3's existing
 * behaviour (an invite/verification link is meant to be visible here,
 * since there's no real inbox to check locally or in dev yet). An
 * attachment's bytes are never logged, only its filename/size/content
 * type — logging a PDF's raw content would be both useless and the kind
 * of thing "never log anything sensitive" is there to prevent.
 */
@Component
@Profile("local | test")
public class ConsoleEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        if (message.attachment() != null) {
            log.info("Email (stub, not actually sent): to={} subject={} body={} attachment={} ({} bytes, {})",
                message.to(), message.subject(), message.body(),
                message.attachment().filename(), message.attachment().content().length, message.attachment().contentType());
        } else {
            log.info("Email (stub, not actually sent): to={} subject={} body={}",
                message.to(), message.subject(), message.body());
        }
    }
}
