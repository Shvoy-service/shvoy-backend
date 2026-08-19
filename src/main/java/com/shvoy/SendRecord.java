package com.shvoy;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The record of one email send attempt (Story 9.4, with 9.6 folded in) —
 * append-only, tenant-scoped ({@code company_id} is Hibernate's {@code @TenantId},
 * set on insert and auto-filtering reads). Answers "did the invite actually
 * send?" (the support question), gives ops a failure surface, and is the
 * substrate any future retry reads (hence the permanent/transient
 * {@link SendOutcome}).
 *
 * <p><strong>Never persists the email body.</strong> Subject and metadata yes,
 * body no — bodies carry invite links, and the send record must not become a
 * token store (2.3's token-hygiene rule, extended to the transport layer).
 */
@Entity
@Table(name = "send_records")
public class SendRecord extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private EmailSource source;

    @Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    @Column(name = "subject", length = 500)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private SendOutcome outcome;

    @Column(name = "ses_message_id", length = 200)
    private String sesMessageId;

    @Column(name = "error", length = 2000)
    private String error;

    @Column(name = "entity_reference", length = 200)
    private String entityReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SendRecord() {
    }

    public SendRecord(EmailSource source, String recipient, String subject, SendOutcome outcome,
            String sesMessageId, String error, String entityReference) {
        this.source = source;
        this.recipient = recipient;
        this.subject = subject;
        this.outcome = outcome;
        this.sesMessageId = sesMessageId;
        this.error = error;
        this.entityReference = entityReference;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public EmailSource getSource() {
        return source;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public SendOutcome getOutcome() {
        return outcome;
    }

    public String getSesMessageId() {
        return sesMessageId;
    }

    public String getError() {
        return error;
    }

    public String getEntityReference() {
        return entityReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
