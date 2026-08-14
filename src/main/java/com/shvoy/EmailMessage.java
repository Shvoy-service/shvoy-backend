package com.shvoy;

/**
 * A general email to send — deliberately not tied to any one flow. Two
 * consumers so far: {@code InvitationService} (Story 2.3, invite/
 * verification links, no attachment) and {@code PurchaseOrderSendService}
 * (Story 4.7, the generated PO PDF as {@code attachment}). {@code
 * attachment} is null for a plain message.
 *
 * See {@link EmailSender}'s Javadoc for why this exists as its own seam
 * rather than each flow logging/sending independently.
 */
public record EmailMessage(
    String to,
    String subject,
    String body,
    EmailAttachment attachment
) {

    public EmailMessage(String to, String subject, String body) {
        this(to, subject, body, null);
    }
}
