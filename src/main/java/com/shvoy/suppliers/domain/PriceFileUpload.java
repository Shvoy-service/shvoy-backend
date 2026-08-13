package com.shvoy.suppliers.domain;

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
 * An audit record of a successful price-file upload — see Story 3.5. Only
 * successful uploads are recorded here: the raw file itself is the audit
 * trail for a failed/rejected upload (stored in S3 unconditionally, before
 * validation — see PriceFileUploadService), and the error response already
 * tells the caller what to fix, so a DB row for a failed attempt isn't
 * needed on top of that.
 */
@Entity
@Table(name = "price_file_uploads")
public class PriceFileUpload extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected PriceFileUpload() {
    }

    public PriceFileUpload(UUID supplierId, String s3Key, int rowCount) {
        this.supplierId = supplierId;
        this.s3Key = s3Key;
        this.rowCount = rowCount;
        this.uploadedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public String getS3Key() {
        return s3Key;
    }

    public int getRowCount() {
        return rowCount;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
