package com.shvoy.suppliers.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.domain.SupplierAuditEvent;
import com.shvoy.suppliers.domain.SupplierAuditEventType;
import com.shvoy.suppliers.dto.BankDetailsRequest;
import com.shvoy.suppliers.dto.BankDetailsResponse;
import com.shvoy.suppliers.dto.ComplianceRequest;
import com.shvoy.suppliers.dto.SupplierResponse;
import com.shvoy.suppliers.dto.UnvalidateSupplierRequest;
import com.shvoy.suppliers.repository.SupplierAuditEventRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * The supplier-validation lifecycle (supplier remodel): bank details, compliance,
 * and the explicit human approval that makes a supplier PO-eligible.
 *
 * <p><strong>Readiness vs validation are kept distinct:</strong> the system
 * <em>derives</em> "ready for validation" (bank details + compliance present);
 * a human <em>approves</em> ({@link #validate}, only-when-ready). And the
 * load-bearing control: {@link #updateBankDetails} on a VALIDATED supplier
 * reverts it to PENDING and audits loudly — a changed bank account on a trusted
 * supplier is the payment-fraud pattern the whole lifecycle exists to catch.
 */
@Service
public class SupplierValidationService {

    private final SupplierRepository supplierRepository;
    private final SupplierAuditEventRepository auditRepository;

    SupplierValidationService(SupplierRepository supplierRepository, SupplierAuditEventRepository auditRepository) {
        this.supplierRepository = supplierRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public SupplierResponse updateBankDetails(UUID supplierId, BankDetailsRequest request) {
        Supplier supplier = findOwnSupplier(supplierId);
        boolean reverted = supplier.updateBankDetails(request.accountName(), request.accountNumber(),
            request.sortCode());
        supplierRepository.save(supplier);
        audit(supplier, SupplierAuditEventType.BANK_DETAILS_CHANGED,
            "Bank details changed (account ending " + supplier.maskedBankAccountNumber() + ")");
        if (reverted) {
            audit(supplier, SupplierAuditEventType.BANK_CHANGE_REVERTED_VALIDATION,
                "Validation reverted VALIDATED → PENDING because bank details changed — re-validation required");
        }
        return SupplierService.toResponse(supplier);
    }

    /** The dedicated FINANCE/ADMIN-only full read — never in the default supplier response. */
    @Transactional(readOnly = true)
    public BankDetailsResponse getBankDetails(UUID supplierId) {
        Supplier supplier = findOwnSupplier(supplierId);
        return new BankDetailsResponse(supplier.getBankAccountName(), supplier.getBankAccountNumber(),
            supplier.getBankSortCode());
    }

    @Transactional
    public SupplierResponse setCompliance(UUID supplierId, ComplianceRequest request) {
        Supplier supplier = findOwnSupplier(supplierId);
        supplier.setComplianceStatus(request.status());
        supplierRepository.save(supplier);
        return SupplierService.toResponse(supplier);
    }

    @Transactional
    public SupplierResponse validate(UUID supplierId) {
        Supplier supplier = findOwnSupplier(supplierId);
        if (!supplier.isReadyForValidation()) {
            throw new ConflictException(ErrorCode.SUPPLIER_NOT_READY_FOR_VALIDATION,
                "Supplier is not ready for validation — bank details and confirmed compliance are required first");
        }
        supplier.validate();
        supplierRepository.save(supplier);
        audit(supplier, SupplierAuditEventType.VALIDATED, "Supplier validated — now PO-eligible");
        return SupplierService.toResponse(supplier);
    }

    @Transactional
    public SupplierResponse unvalidate(UUID supplierId, UnvalidateSupplierRequest request) {
        Supplier supplier = findOwnSupplier(supplierId);
        supplier.unvalidate();
        supplierRepository.save(supplier);
        audit(supplier, SupplierAuditEventType.UNVALIDATED, "Supplier un-validated — reason: " + request.reason());
        return SupplierService.toResponse(supplier);
    }

    private Supplier findOwnSupplier(UUID id) {
        Supplier supplier = supplierRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);
        return supplier;
    }

    private void audit(Supplier supplier, SupplierAuditEventType type, String detail) {
        auditRepository.save(new SupplierAuditEvent(supplier.getId(), type, detail, CurrentUserContext.getOrNull()));
    }
}
