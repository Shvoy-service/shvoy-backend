package com.shvoy;

/**
 * Abstraction over outbound email delivery — the Notifications feature's
 * eventual SES integration point. Deliberately general, not PO- or invite-
 * specific: {@code InvitationService} (2.3) and {@code PurchaseOrderSendService}
 * (4.7) are its two consumers, and both should light up together the day a
 * real implementation replaces {@link ConsoleEmailSender}, rather than each
 * flow needing its own separate swap. Same principle as {@link IdentityProvider}
 * — define the interface now, real implementation later — see that type's
 * Javadoc.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
