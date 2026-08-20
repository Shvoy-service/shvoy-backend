package com.shvoy;

/**
 * The subject + body a composer produces (Story 9.5) — the content half of an
 * email, separated from both transport ({@link EmailSender} takes subject +
 * body, unchanged) and business logic (a consumer resolves the recipient,
 * attachment, {@link EmailSource} and reference, then assembles the {@link
 * EmailMessage} around this content).
 *
 * <p>Plain text for MVP. The per-consumer composer is the seam that makes an
 * HTML body a later swap without touching the sender or the consumers.
 */
public record EmailContent(String subject, String body) {
}
