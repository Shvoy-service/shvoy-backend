package com.shvoy.suppliers.domain;

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

import com.shvoy.TenantScoped;

/**
 * The core supplier record — see Story 3.1. Deliberately lean: payment
 * terms (3.3), prices/SKUs (3.4+), discount tiers (3.6), and carton sizes
 * (3.7) are all separate entities in later stories, not columns here.
 *
 * Extends {@link TenantScoped} exactly like {@code User} does (see
 * onboarding.domain.User) — {@code company_id} is populated and enforced by
 * Hibernate automatically (see TenancyConfig), so every query against
 * suppliers is transparently constrained to the caller's company with no
 * per-query filtering.
 *
 * No money fields exist on this entity. When they arrive on later child
 * entities (price files, discount tiers), they follow the merged
 * string+currency wire format / BigDecimal convention — see {@link
 * com.shvoy.Money} and docs/CONTRACT.md — not a bare BigDecimal/double
 * column.
 */
@Entity
@Table(name = "suppliers")
public class Supplier extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierStatus status;

    @Column(length = 100)
    private String country;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    /** The supplier's default incoterm (PO-issuance gate) — pre-filled onto a new PO, editable per order. */
    @Column(name = "default_incoterms", length = 10)
    private String defaultIncoterms;

    /** The supplier's current/target payment terms (supplier remodel) — loose id refs, not JPA relationships. */
    @Column(name = "current_term_id")
    private UUID currentTermId;

    @Column(name = "target_term_id")
    private UUID targetTermId;

    /** The validation lifecycle — distinct from {@link #status} (ACTIVE/INACTIVE). New suppliers start PENDING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 20)
    private SupplierValidationStatus validationStatus;

    @Column(name = "bank_account_name", length = 255)
    private String bankAccountName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    @Column(name = "bank_sort_code", length = 20)
    private String bankSortCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_status", length = 20)
    private ComplianceStatus complianceStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Supplier() {
    }

    public Supplier(String name, String country, String contactEmail, String defaultIncoterms) {
        this.name = name;
        this.country = country;
        this.contactEmail = contactEmail;
        this.defaultIncoterms = defaultIncoterms;
        this.status = SupplierStatus.ACTIVE;
        this.validationStatus = SupplierValidationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    // --- payment terms (supplier remodel) ---

    /** Set the current term (the required slot once terms exist). */
    public void setCurrentTerm(UUID termId) {
        this.currentTermId = termId;
        this.updatedAt = Instant.now();
    }

    /** Set (or replace) the target term — the mid-transition slot. */
    public void setTargetTerm(UUID termId) {
        this.targetTermId = termId;
        this.updatedAt = Instant.now();
    }

    /** Promote target → current; the old current term row is retained historically (just no longer referenced). */
    public UUID activateTarget() {
        UUID previousCurrent = this.currentTermId;
        this.currentTermId = this.targetTermId;
        this.targetTermId = null;
        this.updatedAt = Instant.now();
        return previousCurrent;
    }

    // --- validation lifecycle (supplier remodel) ---

    /**
     * Update bank details. <strong>The control:</strong> changing them on a
     * {@code VALIDATED} supplier reverts it to {@code PENDING} — a changed bank
     * account on a trusted supplier is the invoice-fraud pattern this lifecycle
     * exists to catch, so re-validation is required (friction on purpose).
     * Returns whether it reverted, for a loud audit.
     */
    public boolean updateBankDetails(String accountName, String accountNumber, String sortCode) {
        this.bankAccountName = accountName;
        this.bankAccountNumber = accountNumber;
        this.bankSortCode = sortCode;
        this.updatedAt = Instant.now();
        if (validationStatus == SupplierValidationStatus.VALIDATED) {
            this.validationStatus = SupplierValidationStatus.PENDING;
            return true;
        }
        return false;
    }

    public void setComplianceStatus(ComplianceStatus complianceStatus) {
        this.complianceStatus = complianceStatus;
        this.updatedAt = Instant.now();
    }

    /** Derived: required fields present (bank details + compliance confirmed). A human still approves. */
    public boolean isReadyForValidation() {
        return bankAccountNumber != null && !bankAccountNumber.isBlank()
            && complianceStatus == ComplianceStatus.CONFIRMED;
    }

    public void validate() {
        this.validationStatus = SupplierValidationStatus.VALIDATED;
        this.updatedAt = Instant.now();
    }

    public void unvalidate() {
        this.validationStatus = SupplierValidationStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    /** The bank account number for general display — last 4 characters only, the rest masked. */
    public String maskedBankAccountNumber() {
        if (bankAccountNumber == null || bankAccountNumber.isBlank()) {
            return null;
        }
        int keep = Math.min(4, bankAccountNumber.length());
        return "•".repeat(bankAccountNumber.length() - keep) + bankAccountNumber.substring(bankAccountNumber.length() - keep);
    }

    /**
     * Full-replace semantics (see Story 3.2) — a field omitted from the
     * update request clears the corresponding column, same convention as
     * Company.updateProfile.
     */
    public void updateDetails(String name, String country, String contactEmail, String defaultIncoterms) {
        this.name = name;
        this.country = country;
        this.contactEmail = contactEmail;
        this.defaultIncoterms = defaultIncoterms;
        this.updatedAt = Instant.now();
    }

    /**
     * Soft delete only, same pattern as User.deactivate() (2.6) — the row
     * stays since price files and, later, POs must keep pointing at a real
     * supplier rather than a dangling id.
     */
    public void deactivate() {
        this.status = SupplierStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public SupplierStatus getStatus() {
        return status;
    }

    public String getCountry() {
        return country;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getDefaultIncoterms() {
        return defaultIncoterms;
    }

    public UUID getCurrentTermId() {
        return currentTermId;
    }

    public UUID getTargetTermId() {
        return targetTermId;
    }

    public SupplierValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public String getBankAccountName() {
        return bankAccountName;
    }

    public String getBankAccountNumber() {
        return bankAccountNumber;
    }

    public String getBankSortCode() {
        return bankSortCode;
    }

    public ComplianceStatus getComplianceStatus() {
        return complianceStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
