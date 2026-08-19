package com.shvoy.suppliers.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.UnitPrice;
import com.shvoy.ValidationException;
import com.shvoy.suppliers.domain.DiscountTier;
import com.shvoy.suppliers.domain.Sku;
import com.shvoy.suppliers.domain.SkuPrice;
import com.shvoy.suppliers.domain.SkuStatus;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.dto.CreateSkuRequest;
import com.shvoy.suppliers.dto.CurrentPriceView;
import com.shvoy.suppliers.dto.DiscountTierResponse;
import com.shvoy.suppliers.dto.SkuPriceRequest;
import com.shvoy.suppliers.dto.SkuPriceResponse;
import com.shvoy.suppliers.dto.SkuResponse;
import com.shvoy.suppliers.dto.SkuSummary;
import com.shvoy.suppliers.dto.SkuWithPriceResponse;
import com.shvoy.suppliers.dto.SupplierSkuView;
import com.shvoy.suppliers.dto.UpdateSkuRequest;
import com.shvoy.suppliers.repository.DiscountTierRepository;
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
 *
 * {@link #getSummary} is this class's cross-module surface (Story 4.6) —
 * {@code @NamedInterface}, same pattern as {@code SupplierService}/{@code
 * PaymentTermsService}, so another module (purchaseorders, for its PO
 * document) can look up a SKU's code/description without {@code
 * SkuRepository}/{@code Sku} being exposed directly.
 */
@NamedInterface("suppliers")
@Service
public class SkuService {

    private final SkuRepository skuRepository;
    private final SkuPriceRepository skuPriceRepository;
    private final DiscountTierRepository discountTierRepository;
    private final SupplierRepository supplierRepository;
    private final JdbcTemplate jdbcTemplate;

    SkuService(SkuRepository skuRepository, SkuPriceRepository skuPriceRepository,
            DiscountTierRepository discountTierRepository, SupplierRepository supplierRepository,
            JdbcTemplate jdbcTemplate) {
        this.skuRepository = skuRepository;
        this.skuPriceRepository = skuPriceRepository;
        this.discountTierRepository = discountTierRepository;
        this.supplierRepository = supplierRepository;
        this.jdbcTemplate = jdbcTemplate;
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
        sku.update(request.code(), request.description(), request.status(), request.cartonSize());
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
     *
     * {@link #lockSkuForPriceWrite} is called first because the decision
     * below is a plain read-then-decide over {@code existing}, with no DB
     * constraint backing it up (unlike DUPLICATE_SKU/DUPLICATE_SUPPLIER,
     * which both have a unique index as a race-safety-net — see V10/V14).
     * Without serializing here, two concurrent calls for the same SKU could
     * both read the same snapshot under READ COMMITTED, both decide
     * they're the sole superseding write, and corrupt the timeline (e.g.
     * both blindly overwrite the same open row's {@code validTo} to
     * different values — there's no {@code @Version} field anywhere in
     * this codebase, so it'd be a silent last-write-wins, not a detected
     * conflict).
     */
    private SkuPrice insertPrice(UUID skuId, UnitPrice unitPrice, LocalDate validFrom, LocalDate validTo) {
        lockSkuForPriceWrite(skuId);

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

    /**
     * A plain row lock on the parent Sku, held for the rest of this
     * transaction — a second transaction's own SELECT ... FOR UPDATE for
     * the same SKU blocks here until the first commits, so by the time it
     * proceeds, the {@code existing} prices it reads below already reflect
     * the first transaction's write. Locking the Sku row rather than
     * individual SkuPrice rows is deliberate: it's the one row guaranteed
     * to already exist before any price for it does (createSku locks the
     * row it just inserted in the same transaction — a harmless no-op,
     * since nothing else can see it yet), so there's always something to
     * lock even for a SKU's very first price.
     */
    private void lockSkuForPriceWrite(UUID skuId) {
        jdbcTemplate.queryForObject("SELECT id FROM skus WHERE id = ? FOR UPDATE", UUID.class, skuId);
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

    /**
     * {@code saveAndFlush}, not {@code save}: {@link #createSku} calls
     * {@link #insertPrice} (and so {@link #lockSkuForPriceWrite}) with this
     * SKU's id immediately afterwards, and that lock query is raw JDBC —
     * it queries the {@code skus} table directly, bypassing Hibernate's
     * session, so it needs this row actually written, not just pending in
     * the persistence context. Flushing here also means the
     * DataIntegrityViolationException below is caught at the point this
     * method is called, not deferred to whenever the transaction next
     * happens to flush.
     */
    private Sku saveGuardingCodeUniqueness(Sku sku) {
        try {
            return skuRepository.saveAndFlush(sku);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(ErrorCode.DUPLICATE_SKU, "SKU code already exists for this supplier: " + sku.getCode());
        }
    }

    /**
     * The supplier screen's one-call read (Story — supplier SKU read
     * endpoint): a supplier's active SKUs, each with carton size (already on
     * {@link SkuResponse}), its current price + derived in-date flag, and
     * that price's discount tiers inline. History is deliberately excluded —
     * see {@link SupplierSkuView}.
     *
     * <p>Bounded queries, not N+1: one {@code findAll} each for prices and
     * tiers, grouped in Java (same findAll-and-filter convention as the rest
     * of this service — see the class Javadoc), rather than a per-SKU price
     * lookup and a per-price tier lookup. Hundreds of SKUs per supplier is
     * the realistic size, so the query count must not scale with it.
     *
     * <p>Current-price selection is {@link SkuPriceSelection#current} — the
     * exact same row-selection {@link PriceResolutionService} resolves
     * against, so the in-date flag here and "a valid price exists today"
     * there never diverge.
     */
    @Transactional(readOnly = true)
    public List<SupplierSkuView> listSkus(UUID supplierId) {
        findOwnSupplier(supplierId);
        LocalDate today = LocalDate.now();

        List<Sku> skus = skuRepository.findAll().stream()
            .filter(s -> s.getSupplierId().equals(supplierId) && s.getStatus() == SkuStatus.ACTIVE)
            .sorted(Comparator.comparing(Sku::getCode))
            .toList();

        Map<UUID, List<SkuPrice>> pricesBySku = skuPriceRepository.findAll().stream()
            .collect(Collectors.groupingBy(SkuPrice::getSkuId));
        Map<UUID, List<DiscountTier>> tiersByPrice = discountTierRepository.findAll().stream()
            .collect(Collectors.groupingBy(DiscountTier::getSkuPriceId));

        return skus.stream()
            .map(sku -> toView(sku, pricesBySku.getOrDefault(sku.getId(), List.of()), tiersByPrice, today))
            .toList();
    }

    private static SupplierSkuView toView(Sku sku, List<SkuPrice> pricesForSku,
            Map<UUID, List<DiscountTier>> tiersByPrice, LocalDate today) {
        Optional<SkuPrice> current = SkuPriceSelection.current(pricesForSku);
        if (current.isEmpty()) {
            return new SupplierSkuView(toSkuResponse(sku), null, List.of());
        }

        SkuPrice price = current.get();
        CurrentPriceView currentPrice = new CurrentPriceView(price.getId(), price.getSkuId(), price.getUnitPrice(),
            price.getValidFrom(), price.getValidTo(), price.isInDate(today), price.getCreatedAt(), price.getUpdatedAt());

        String currency = price.getUnitPrice().currency();
        List<DiscountTierResponse> tiers = tiersByPrice.getOrDefault(price.getId(), List.of()).stream()
            .sorted(Comparator.comparingInt(DiscountTier::getQuantityThreshold))
            .map(t -> new DiscountTierResponse(t.getId(), t.getQuantityThreshold(),
                new UnitPrice(t.getUnitPriceAmount(), currency), t.getCreatedAt()))
            .toList();

        return new SupplierSkuView(toSkuResponse(sku), currentPrice, tiers);
    }

    @Transactional(readOnly = true)
    public SkuSummary getSummary(UUID supplierId, UUID skuId) {
        Sku sku = findOwnSkuUnderSupplier(supplierId, skuId);
        return new SkuSummary(sku.getId(), sku.getCode(), sku.getDescription());
    }

    /**
     * Company-wide existence check (Story 5.2) — deliberately not scoped to
     * a single supplier, unlike {@link #getSummary}: a PI line referencing
     * a SKU that exists in the company but under a different supplier than
     * the PO's is a genuine discrepancy for reconciliation (5.3) to surface,
     * not an unknowable typo. Only a SKU id that doesn't exist in the
     * company at all is rejected here — see {@code ProformaInvoiceService}'s
     * "record faithfully, judge later" validation posture.
     */
    @Transactional(readOnly = true)
    public void assertOwnSkuExists(UUID skuId) {
        Sku sku = skuRepository.findById(skuId).orElseThrow(() -> new NotFoundException("SKU not found"));
        TenantGuard.assertOwned(sku);
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
            sku.getStatus(), sku.getCartonSize(), sku.getCreatedAt(), sku.getUpdatedAt());
    }

    private static SkuPriceResponse toSkuPriceResponse(SkuPrice price) {
        return new SkuPriceResponse(price.getId(), price.getSkuId(), price.getUnitPrice(),
            price.getValidFrom(), price.getValidTo(), price.getCreatedAt(), price.getUpdatedAt());
    }
}
