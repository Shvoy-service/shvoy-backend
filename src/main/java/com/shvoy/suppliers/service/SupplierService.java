package com.shvoy.suppliers.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.domain.SupplierStatus;
import com.shvoy.suppliers.dto.SupplierRequest;
import com.shvoy.suppliers.dto.SupplierResponse;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Filters/sorts/matches in Java over findAll(), rather than custom
 * repository query methods — same pattern as TeamManagementService, for the
 * same reason (see SupplierRepository's Javadoc): a custom derived query
 * method on a @TenantId entity gets validated against Hibernate at
 * repository-bean-creation time, before any tenant is resolvable.
 *
 * Plain {@code @Transactional}, not the TransactionTemplate dance
 * RegistrationService needs — every method here runs against a tenant
 * already established before the controller was even invoked (see
 * TenantContextFilter), unlike registration/invite-acceptance, which have
 * to establish the tenant as part of the operation itself. This mirrors
 * TeamManagementService/CompanyProfileService, not RegistrationService.
 *
 * {@link #assertOwnSupplierExists} is this class's cross-module surface
 * (Story 4.4) — {@code @NamedInterface}, same pattern as
 * {@code PriceResolutionService}/{@code PaymentTermsService}, so another
 * module (purchaseorders) can confirm a supplier id belongs to its own
 * tenant without {@code SupplierRepository}/{@code Supplier} being exposed
 * directly.
 */
@NamedInterface("suppliers")
@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;

    SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        assertNameAvailable(request.name(), null);
        Supplier supplier = new Supplier(request.name(), request.country(), request.contactEmail());
        return toResponse(saveGuardingUniqueness(supplier, request.name()));
    }

    /**
     * Active suppliers only by default, sorted by name — see Story 3.2's
     * documented default. {@code includeInactive=true} opts into seeing
     * deactivated ones too. No pagination: out of scope for the pilot (see
     * SupplierController).
     */
    @Transactional(readOnly = true)
    public List<SupplierResponse> list(boolean includeInactive) {
        return supplierRepository.findAll().stream()
            .filter(s -> includeInactive || s.getStatus() == SupplierStatus.ACTIVE)
            .sorted(Comparator.comparing(Supplier::getName, String.CASE_INSENSITIVE_ORDER))
            .map(SupplierService::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(UUID id) {
        return toResponse(findOwnSupplier(id));
    }

    @Transactional
    public SupplierResponse update(UUID id, SupplierRequest request) {
        Supplier supplier = findOwnSupplier(id);
        assertNameAvailable(request.name(), id);
        supplier.updateDetails(request.name(), request.country(), request.contactEmail());
        return toResponse(saveGuardingUniqueness(supplier, request.name()));
    }

    @Transactional
    public SupplierResponse deactivate(UUID id) {
        Supplier supplier = findOwnSupplier(id);
        supplier.deactivate();
        return toResponse(supplierRepository.save(supplier));
    }

    /**
     * Story 4.6's other cross-module surface: the minimal supplier detail a
     * PO document needs to display, not the full {@link SupplierResponse}
     * shape (status/timestamps a customer-facing document has no business
     * showing) — see {@link SupplierSummary}'s Javadoc.
     */
    @Transactional(readOnly = true)
    public SupplierSummary getSummary(UUID id) {
        Supplier supplier = findOwnSupplier(id);
        return new SupplierSummary(supplier.getId(), supplier.getName(), supplier.getCountry(), supplier.getContactEmail());
    }

    /**
     * Throws the same {@link NotFoundException} a missing or cross-tenant
     * id would throw anywhere else in this module — the cross-module
     * ownership check itself, with no response body to leak beyond that.
     */
    @Transactional(readOnly = true)
    public void assertOwnSupplierExists(UUID id) {
        findOwnSupplier(id);
    }

    /**
     * The findAll()-based pre-check above already rejects the common case
     * with a clean error; this catches only the narrow race where two
     * concurrent requests both pass that check. The DB constraint it relies
     * on is case-sensitive (see V10's migration), so two concurrent
     * requests differing only in case could both still succeed — an
     * accepted, narrow residual gap rather than reason to complicate the
     * index.
     */
    private Supplier saveGuardingUniqueness(Supplier supplier, String name) {
        try {
            return supplierRepository.save(supplier);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(ErrorCode.DUPLICATE_SUPPLIER, "Supplier already exists: " + name);
        }
    }

    private void assertNameAvailable(String name, UUID excludingId) {
        boolean taken = supplierRepository.findAll().stream()
            .anyMatch(s -> s.getName().equalsIgnoreCase(name) && !s.getId().equals(excludingId));
        if (taken) {
            throw new ConflictException(ErrorCode.DUPLICATE_SUPPLIER, "Supplier already exists: " + name);
        }
    }

    private Supplier findOwnSupplier(UUID id) {
        Supplier supplier = supplierRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);
        return supplier;
    }

    static SupplierResponse toResponse(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getName(), supplier.getStatus(),
            supplier.getCountry(), supplier.getContactEmail(),
            supplier.getValidationStatus(), supplier.isReadyForValidation(), supplier.getComplianceStatus(),
            supplier.maskedBankAccountNumber(), supplier.getBankAccountNumber() != null,
            supplier.getCreatedAt(), supplier.getUpdatedAt());
    }
}
