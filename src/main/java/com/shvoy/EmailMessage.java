package com.shvoy;

/**
 * A general email to send — deliberately not tied to any one flow. Four
 * consumers: {@code InvitationService} (2.3), {@code PurchaseOrderSendService}
 * (4.7), {@code ApproverNotifier} (5.5), {@code DiscrepancyNotifier} (6.6).
 * {@code attachment} is null for a plain message.
 *
 * <p>{@code source} + {@code entityReference} (Story 9.4) tag the message's
 * origin for the send record — metadata only, so the {@link EmailSender} contract
 * and consumer behaviour are unchanged. The convenience constructors default them
 * ({@code OTHER}, null) so pre-9.4 call shapes still compile.
 *
 * See {@link EmailSender}'s Javadoc for why this exists as its own seam.
 */
public record EmailMessage(
    String to,
    String subject,
    String body,
    EmailAttachment attachment,
    EmailSource source,
    String entityReference
) {

    public EmailMessage(String to, String subject, String body) {
        this(to, subject, body, null, EmailSource.OTHER, null);
    }

    public EmailMessage(String to, String subject, String body, EmailAttachment attachment) {
        this(to, subject, body, attachment, EmailSource.OTHER, null);
    }

    public EmailMessage(String to, String subject, String body, EmailSource source, String entityReference) {
        this(to, subject, body, null, source, entityReference);
    }

    public EmailMessage(String to, String subject, String body, EmailAttachment attachment,
            EmailSource source, String entityReference) {
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.attachment = attachment;
        this.source = source == null ? EmailSource.OTHER : source;
        this.entityReference = entityReference;
    }
}
