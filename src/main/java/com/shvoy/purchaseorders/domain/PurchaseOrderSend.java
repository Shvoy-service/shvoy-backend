package com.shvoy.purchaseorders.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * An immutable audit record of a Story 4.7 send — who sent the PO, when, to
 * which recipient email, and which document was actually sent (a snapshot
 * of the PO's {@code document_s3_key} at send time, not a live reference —
 * same "snapshot, not live reference" principle used throughout this
 * module, e.g. a PO line's price). Construct-only, no mutators at all, same
 * immutable-audit-record shape as {@code PurchaseOrderPriceOverride} (4.5)
 * and {@code PriceFileUpload} (3.5).
 *
 * Resending an already-{@code SENT} PO (allowed — see {@code
 * PurchaseOrderSendService}) appends a new row here rather than updating
 * one; a PO can have several of these over its lifetime, one per send
 * attempt.
 */
@Entity
@Table(name = "purchase_order_sends")
public class PurchaseOrderSend extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "sent_by", nullable = false)
    private UUID sentBy;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(name = "document_s3_key", nullable = false, length = 500)
    private String documentS3Key;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected PurchaseOrderSend() {
    }

    public PurchaseOrderSend(UUID purchaseOrderId, UUID sentBy, String recipientEmail, String documentS3Key) {
        this.purchaseOrderId = purchaseOrderId;
        this.sentBy = sentBy;
        this.recipientEmail = recipientEmail;
        this.documentS3Key = documentS3Key;
        this.sentAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID getSentBy() {
        return sentBy;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getDocumentS3Key() {
        return documentS3Key;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
