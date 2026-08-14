package com.shvoy;

/**
 * A file attached to an {@link EmailMessage} — first needed by Story 4.7 to
 * carry the generated PO's PDF. {@code content} is the raw bytes, not an S3
 * reference: an {@link EmailSender} implementation (a future SES one, most
 * likely) needs the actual attachment content to send, not a pointer the
 * caller resolved itself.
 */
public record EmailAttachment(
    String filename,
    String contentType,
    byte[] content
) {
}
