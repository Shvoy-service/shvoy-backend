package com.shvoy.suppliers.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.UnitPrice;
import com.shvoy.ValidationException;
import com.shvoy.suppliers.domain.Sku;
import com.shvoy.suppliers.domain.SkuPrice;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.dto.CreateSkuRequest;
import com.shvoy.suppliers.dto.SkuPriceRequest;
import com.shvoy.suppliers.dto.SkuPriceResponse;
import com.shvoy.suppliers.dto.SkuResponse;
import com.shvoy.suppliers.dto.SkuWithPriceResponse;
import com.shvoy.suppliers.dto.UpdateSkuRequest;
import com.shvoy.suppliers.repository.SkuPriceRepository;
import com.shvoy.suppliers.repository.SkuRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Story 3.5: SKU/price entry. Filters/matches in Java over findAll()
 * rather than custom repository query methods, same reasoning as
 * SupplierService/PaymentTermsService — see SupplierRepository's Javadoc.
 * PriceFileUploadService (bulk upload) calls the same {@link #createSku}/
 * {@link #addPrice} methods per row, so manual entry and upload rows go
 * through identical validation/supersession logic.
 */
@Service
public class SkuService {

    private final SkuRepository skuRepository;
    private final SkuPriceRepository skuPriceRepository;
    private final SupplierRepository supplierRepository;

    SkuService(SkuRepository skuRepository, SkuPriceRepository skuPriceRepository,
            SupplierRepository supplierRepository) {
        this.skuRepository = skuRepository;
        this.skuPriceRepository = skuPriceRepository;
        this.supplierRepository = supplierRepository;
    }

    @Transactional
    public SkuWithPriceResponse createSku(UUID supplierId, CreateSkuRequest request) {
        findOwnSupplier(supplierId);
        UnitPrice unitPrice = buildUnitPrice(request.unitPriceAmount(), request.currency());
        validateWindow(request.validFrom(), request.validTo());
        assertCodeAvailable(supplierId, request.code(), null);

        Sku sku = saveGuardingCodeUniqueness(new Sku(supplierId, request.code(), request.description()));
        SkuPrice price = insertPrice(sku.getId(), unitPrice, request.validFrom(), request.validTo());
        return new SkuWithPriceResponse(toSkuResponse(sku), toSkuPriceResponse(price));
    }

    @Transactional
    public SkuResponse updateSku(UUID supplierId, UUID skuId, UpdateSkuRequest request) {
        Sku sku = findOwnSkuUnderSupplier(supplierId, skuId);
        assertCodeAvailable(supplierId, request.code(), skuId);
        sku.update(request.code(), request.description(), request.status());
        return toSkuResponse(saveGuardingCodeUniqueness(sku));
    }

    /**
     * Used by PriceFileUploadService to decide, per CSV row, whether a code
     * already has a SKU to add a price version to, or needs one created —
     * findAll()+filter rather than a custom finder, same reasoning as
     * assertCodeAvailable.
     */
    @Transactional(readOnly = true)
    Optional<UUID> findExistingSkuId(UUID supplierId, String code) {
        return skuRepository.findAll().stream()
            .filter(s -> s.getSupplierId().equals(supplierId) && s.getCode().equalsIgnoreCase(code))
            .map(Sku::getId)
            .findFirst();
    }

    @Transactional
    public SkuPriceResponse addPrice(UUID supplierId, UUID skuId, SkuPriceRequest request) {
        Sku sku = findOwnSkuUnderSupplier(supplierId, skuId);
        UnitPrice unitPrice = buildUnitPrice(request.unitPriceAmount(), request.currency());
        validateWindow(request.validFrom(), request.validTo());
        return toSkuPriceResponse(insertPrice(sku.getId(), unitPrice, request.validFrom(), request.validTo()));
    }

    /**
     * The supersession rule (Story 3.5): a SKU's prices form a
     * non-overlapping timeline. Adding a later-dated price against the
     * currently-open row auto-closes that row the day before the new
     * price's start; anything ambiguous (a backdated start, an overlap, or
     * a gap against the latest bounded row when there's no open row to
     * supersede) is rejected rather than guessed at — see
     * docs/CONTRACT.md's SKU & price model section.
     */
    private SkuPrice insertPrice(UUID skuId, UnitPrice unitPrice, LocalDate validFrom, LocalDate validTo) {
        List<SkuPrice> existing = skuPriceRepository.findAll().stream()
            .filter(p -> p.getSkuId().equals(skuId))
            .toList();

        if (!existing.isEmpty()) {
            Optional<SkuPrice> openRow = existing.stream().filter(p -> p.getValidTo() == null).findFirst();
            if (openRow.isPresent()) {
                SkuPrice open = openRow.get();
                if (!validFrom.isAfter(open.getValidFrom())) {
                    throw new ConflictException(ErrorCode.AMBIGUOUS_PRICE_WINDOW,
                        "New price must start after the current price's start date (" + open.getValidFrom() + ")");
                }
                LocalDate closeAt = validFrom.minusDays(1);
                if (existing.stream().anyMatch(p -> p != open && overlaps(p, validFrom, validTo))) {
                    throw new ConflictException(ErrorCode.AMBIGUOUS_PRICE_WINDOW,
                        "New price window overlaps an existing price for this SKU");
                }
                open.close(closeAt);
                skuPriceRepository.save(open);
            } else {
                SkuPrice latest = existing.stream()
                    .max(Comparator.comparing(SkuPrice::getValidTo))
                    .orElseThrow();
                LocalDate expectedStart = latest.getValidTo().plusDays(1);
                if (!validFrom.equals(expectedStart) || existing.stream().anyMatch(p -> overlaps(p, validFrom, validTo))) {
                    throw new ConflictException(ErrorCode.AMBIGUOUS_PRICE_WINDOW,
                        "New price window must start immediately after the latest existing price ("
                            + latest.getValidTo() + ") with no gap or overlap");
                }
            }
        }

        return skuPriceRepository.save(new SkuPrice(skuId, unitPrice, validFrom, validTo));
    }

    private static boolean overlaps(SkuPrice existing, LocalDate newFrom, LocalDate newTo) {
        boolean startsBeforeExistingEnds = existing.getValidTo() == null || !newFrom.isAfter(existing.getValidTo());
        boolean endsAfterExistingStarts = newTo == null || !newTo.isBefore(existing.getValidFrom());
        return startsBeforeExistingEnds && endsAfterExistingStarts;
    }

    private static void validateWindow(LocalDate validFrom, LocalDate validTo) {
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new ValidationException("validTo must not be before validFrom");
        }
    }

    private static UnitPrice buildUnitPrice(BigDecimal amount, String currency) {
        try {
            return new UnitPrice(amount, currency);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("currency: " + e.getMessage());
        }
    }

    private void assertCodeAvailable(UUID supplierId, String code, UUID excludingSkuId) {
        boolean taken = skuRepository.findAll().stream()
            .anyMatch(s -> s.getSupplierId().equals(supplierId)
                && s.getCode().equalsIgnoreCase(code)
                && !s.getId().equals(excludingSkuId));
        if (taken) {
            throw new ConflictException(ErrorCode.DUPLICATE_SKU, "SKU code already exists for this supplier: " + code);
        }
    }

    private Sku saveGuardingCodeUniqueness(Sku sku) {
        try {
            return skuRepository.save(sku);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(ErrorCode.DUPLICATE_SKU, "SKU code already exists for this supplier: " + sku.getCode());
        }
    }

    private Sku findOwnSkuUnderSupplier(UUID supplierId, UUID skuId) {
        findOwnSupplier(supplierId);
        Sku sku = skuRepository.findById(skuId).orElseThrow(() -> new NotFoundException("SKU not found"));
        TenantGuard.assertOwned(sku);
        if (!sku.getSupplierId().equals(supplierId)) {
            throw new NotFoundException("SKU not found");
        }
        return sku;
    }

    private Supplier findOwnSupplier(UUID id) {
        Supplier supplier = supplierRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);
        return supplier;
    }

    private static SkuResponse toSkuResponse(Sku sku) {
        return new SkuResponse(sku.getId(), sku.getSupplierId(), sku.getCode(), sku.getDescription(),
            sku.getStatus(), sku.getCreatedAt(), sku.getUpdatedAt());
    }

    private static SkuPriceResponse toSkuPriceResponse(SkuPrice price) {
        return new SkuPriceResponse(price.getId(), price.getSkuId(), price.getUnitPrice(),
            price.getValidFrom(), price.getValidTo(), price.getCreatedAt(), price.getUpdatedAt());
    }
}
