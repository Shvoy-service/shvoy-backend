package com.shvoy.purchaseorders.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.UnitPrice;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.domain.PurchaseOrder;
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;
import com.shvoy.purchaseorders.domain.PurchaseOrderPriceOverride;
import com.shvoy.purchaseorders.domain.PurchaseOrderPriceOverrideLine;
import com.shvoy.purchaseorders.dto.ExpiredPriceOverrideRequest;
import com.shvoy.purchaseorders.dto.LineOverridePrice;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderPriceOverrideLineRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderPriceOverrideRepository;
import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.service.PriceResolutionService;

/**
 * Story 4.5: the gate 4.6 (generation) must call before finalising a draft
 * PO — see that story's boundary note ("this story owns the block-and-
 * override at finalisation; 4.6 owns generation once this gate passes").
 * Deliberately no controller here, same as 4.2/4.3: this is logic another
 * story's endpoint invokes, not an endpoint of its own — role enforcement
 * (Roadmap v2: override is PURCHASING/ADMIN) is therefore 4.6's
 * {@code @PreAuthorize}, the same way 4.4's line-mutation endpoints already
 * cover 4.2's invocation. Every role check in this codebase lives at the
 * controller boundary (see every {@code @PreAuthorize} usage) — nothing
 * checks roles from inside a service, and this story doesn't introduce a
 * first exception to that.
 */
@Service
public class PurchaseOrderFinalisationGateService {

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PriceResolutionService priceResolutionService;
    private final PurchaseOrderPriceOverrideRepository overrideRepository;
    private final PurchaseOrderPriceOverrideLineRepository overrideLineRepository;

    PurchaseOrderFinalisationGateService(PurchaseOrderService purchaseOrderService,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            PriceResolutionService priceResolutionService,
            PurchaseOrderPriceOverrideRepository overrideRepository,
            PurchaseOrderPriceOverrideLineRepository overrideLineRepository) {
        this.purchaseOrderService = purchaseOrderService;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.priceResolutionService = priceResolutionService;
        this.overrideRepository = overrideRepository;
        this.overrideLineRepository = overrideLineRepository;
    }

    /**
     * Re-resolves every line as of today (never trusting the flags already
     * on the lines — a price valid at draft time could have expired since,
     * see the class Javadoc's story context) and blocks
     * ({@code PO_HAS_EXPIRED_PRICES}/409) if any line has no valid price,
     * unless {@code override} carries a non-blank reason and a manual price
     * for every blocked line — in which case the override is persisted as
     * an immutable audit record and this returns it. Returns empty when the
     * PO had nothing to block on (no override needed or attempted).
     *
     * Only checks the expired-price rule — not PO status, line count, or
     * ETD, which are 4.6's own finalisation preconditions to enforce
     * alongside calling this.
     */
    @Transactional
    public Optional<PurchaseOrderPriceOverride> checkFinalisationGate(
            UUID purchaseOrderId, ExpiredPriceOverrideRequest override) {
        PurchaseOrder purchaseOrder = purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId);
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();

        LocalDate today = LocalDate.now();
        List<BlockedLine> blockedLines = lines.stream()
            .map(line -> new BlockedLine(line,
                priceResolutionService.resolve(purchaseOrder.getSupplierId(), line.getSkuId(), line.getQuantity(), today)))
            .filter(blocked -> !blocked.result().priceFound())
            .toList();

        if (blockedLines.isEmpty()) {
            return Optional.empty();
        }

        assertOverrideCoversEveryBlockedLine(blockedLines, override);

        PurchaseOrderPriceOverride savedOverride = overrideRepository.save(
            new PurchaseOrderPriceOverride(purchaseOrderId, CurrentUserContext.get(), override.reason()));
        for (BlockedLine blocked : blockedLines) {
            UnitPrice manualPrice = buildUnitPrice(findSuppliedPrice(override, blocked.line().getId()));
            overrideLineRepository.save(
                new PurchaseOrderPriceOverrideLine(savedOverride.getId(), blocked.line().getId(), manualPrice));
        }
        return Optional.of(savedOverride);
    }

    private void assertOverrideCoversEveryBlockedLine(List<BlockedLine> blockedLines, ExpiredPriceOverrideRequest override) {
        if (override == null || override.reason() == null || override.reason().isBlank()) {
            throw expiredPricesException(blockedLines);
        }
        boolean missingAManualPrice = blockedLines.stream()
            .anyMatch(blocked -> findSuppliedPriceOrNull(override, blocked.line().getId()) == null);
        if (missingAManualPrice) {
            throw expiredPricesException(blockedLines);
        }
    }

    /**
     * Names the affected lines and — per the story's scope item 4 —
     * distinguishes expired (had a price once, none currently covers
     * today) from never-priced (nothing to fall back on) using
     * {@code PriceResolutionResult#everPriced}. Message-only, same
     * convention as every other {@code ConflictException} in this codebase
     * (e.g. {@code AMBIGUOUS_PRICE_WINDOW}) — {@code ErrorResponse} has no
     * structured payload field for a future controller to expose this more
     * richly than text; that's a call for whichever endpoint (4.6) first
     * actually returns this to a caller.
     */
    private static ConflictException expiredPricesException(List<BlockedLine> blockedLines) {
        String detail = blockedLines.stream()
            .map(blocked -> blocked.line().getId() + " (" + (blocked.result().everPriced() ? "EXPIRED" : "NEVER_PRICED") + ")")
            .collect(Collectors.joining(", "));
        return new ConflictException(ErrorCode.PO_HAS_EXPIRED_PRICES,
            "The following lines have no valid price and must be overridden with a reason and a manual price: " + detail);
    }

    private static LineOverridePrice findSuppliedPrice(ExpiredPriceOverrideRequest override, UUID lineId) {
        LineOverridePrice supplied = findSuppliedPriceOrNull(override, lineId);
        if (supplied == null) {
            throw new IllegalStateException("Expected a supplied override price for line " + lineId);
        }
        return supplied;
    }

    private static LineOverridePrice findSuppliedPriceOrNull(ExpiredPriceOverrideRequest override, UUID lineId) {
        if (override.lines() == null) {
            return null;
        }
        return override.lines().stream()
            .filter(supplied -> lineId.equals(supplied.lineId()))
            .findFirst()
            .orElse(null);
    }

    private static UnitPrice buildUnitPrice(LineOverridePrice supplied) {
        BigDecimal amount = supplied.unitPriceAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("unitPriceAmount must be a positive amount for line " + supplied.lineId());
        }
        try {
            return new UnitPrice(amount, supplied.currency());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("currency: " + e.getMessage());
        }
    }

    private record BlockedLine(PurchaseOrderLine line, PriceResolutionResult result) {
    }
}
