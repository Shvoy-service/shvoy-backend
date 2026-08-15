package com.shvoy.purchaseorders.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.TenantGuard;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.domain.PurchaseOrder;
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;
import com.shvoy.purchaseorders.domain.PurchaseOrderSend;
import com.shvoy.purchaseorders.domain.PurchaseOrderStatus;
import com.shvoy.purchaseorders.dto.CreatePurchaseOrderRequest;
import com.shvoy.purchaseorders.dto.PurchaseOrderLineResponse;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationLine;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationView;
import com.shvoy.purchaseorders.dto.PurchaseOrderSummary;
import com.shvoy.purchaseorders.dto.PurchaseOrderResponse;
import com.shvoy.purchaseorders.dto.UpdateRequestedEtdRequest;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderSendRepository;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Story 4.4: creation, reads, ETD, and cancellation of a draft PO — the
 * line-mutation half of the story is {@link PurchaseOrderLineService},
 * which reuses {@link #findOwnPurchaseOrder}/{@link #assertEditable}/
 * {@link #toResponse} from here rather than duplicating the ownership/
 * status-guard/assembly logic.
 *
 * {@link #assertOwnPurchaseOrderExists}/{@link #assertOwnPurchaseOrderReadyForPi}
 * are this class's cross-module surface (Story 5.2) — {@code @NamedInterface},
 * same pattern as {@code SupplierService}/{@code SkuService}, so {@code
 * reconciliation} can confirm a PO id belongs to its own tenant (and, for
 * logging a PI, is far enough along its lifecycle) without {@code
 * PurchaseOrderRepository}/{@code PurchaseOrder}/{@code PurchaseOrderStatus}
 * being exposed directly.
 */
@NamedInterface("purchase-orders")
@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PurchaseOrderSendRepository purchaseOrderSendRepository;
    private final SupplierService supplierService;
    private final PoNumberGenerator poNumberGenerator;

    PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            PurchaseOrderSendRepository purchaseOrderSendRepository,
            SupplierService supplierService,
            PoNumberGenerator poNumberGenerator) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.purchaseOrderSendRepository = purchaseOrderSendRepository;
        this.supplierService = supplierService;
        this.poNumberGenerator = poNumberGenerator;
    }

    /**
     * The PO number is claimed here, at creation — not deferred to
     * generation (4.6) — a pilot-scale decision the story confirmed:
     * abandoned drafts leave gaps in the sequence, which is an acceptable
     * cost for a simpler model (no separate "assign a number" step later).
     *
     * Supplier ownership is checked via {@code SupplierService}'s
     * {@code @NamedInterface} surface, not {@code SupplierRepository}
     * directly — {@code purchaseorders} never reaches into the suppliers
     * module's internals (caught by {@code ModularityTests} on first
     * attempt, same as 4.3's payment-terms surface).
     */
    @Transactional
    public PurchaseOrderResponse create(CreatePurchaseOrderRequest request) {
        supplierService.assertOwnSupplierExists(request.supplierId());
        String poNumber = poNumberGenerator.claimNext(TenantContext.get());
        PurchaseOrder purchaseOrder = new PurchaseOrder(request.supplierId(), poNumber, CurrentUserContext.get());
        return toResponse(purchaseOrderRepository.save(purchaseOrder));
    }

    /** Newest first; {@code status == null} means unfiltered. No pagination — same pilot-scale call as SupplierService#list. */
    @Transactional(readOnly = true)
    public List<PurchaseOrderResponse> list(PurchaseOrderStatus status) {
        return purchaseOrderRepository.findAll().stream()
            .filter(po -> status == null || po.getStatus() == status)
            .sorted(Comparator.comparing(PurchaseOrder::getCreatedAt).reversed())
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(UUID id) {
        return toResponse(findOwnPurchaseOrder(id));
    }

    @Transactional
    public PurchaseOrderResponse setRequestedEtd(UUID id, UpdateRequestedEtdRequest request) {
        PurchaseOrder purchaseOrder = findOwnPurchaseOrder(id);
        assertEditable(purchaseOrder);
        if (request.requestedEtd().isBefore(LocalDate.now())) {
            throw new ValidationException("requestedEtd must not be in the past");
        }
        purchaseOrder.setRequestedEtd(request.requestedEtd());
        return toResponse(purchaseOrderRepository.save(purchaseOrder));
    }

    /** Soft-delete for a draft — see {@code PurchaseOrder#cancel}. Only reachable while still DRAFT, same guard as every other mutation. */
    @Transactional
    public PurchaseOrderResponse cancel(UUID id) {
        PurchaseOrder purchaseOrder = findOwnPurchaseOrder(id);
        assertEditable(purchaseOrder);
        purchaseOrder.cancel();
        return toResponse(purchaseOrderRepository.save(purchaseOrder));
    }

    /**
     * Throws the same {@link NotFoundException} a missing or cross-tenant
     * id would throw anywhere else in this module — the cross-module
     * ownership check itself, with no response body to leak beyond that.
     * Same pattern as {@code SupplierService#assertOwnSupplierExists}.
     */
    @Transactional(readOnly = true)
    public void assertOwnPurchaseOrderExists(UUID id) {
        findOwnPurchaseOrder(id);
    }

    /**
     * Story 5.2's precondition for logging a PI: the PO must be far enough
     * along its own lifecycle for a supplier's confirmation to make sense
     * against it. {@code GENERATED} qualifies alongside {@code SENT} — the
     * PO may have gone out to the supplier by a means outside the system —
     * but {@code DRAFT} doesn't, since there's nothing final yet to
     * reconcile against. Never returns the {@code PurchaseOrder}/{@code
     * PurchaseOrderStatus} itself, keeping this module's domain internal —
     * same minimal-cross-module-contract reasoning as {@link
     * #assertOwnPurchaseOrderExists}.
     */
    @Transactional(readOnly = true)
    public void assertOwnPurchaseOrderReadyForPi(UUID id) {
        assertFinalised(id, ErrorCode.PO_NOT_READY_FOR_PI, "a PI");
    }

    /**
     * Story 6.4's precondition for logging an invoice — same rule as a PI: the
     * PO must be {@code GENERATED}/{@code SENT}, not {@code DRAFT}. A distinct
     * code so the UI can say specifically why. Never exposes the {@code
     * PurchaseOrder}/{@code PurchaseOrderStatus}.
     */
    @Transactional(readOnly = true)
    public void assertOwnPurchaseOrderReadyForInvoice(UUID id) {
        assertFinalised(id, ErrorCode.PO_NOT_READY_FOR_INVOICE, "an invoice");
    }

    /**
     * Story 7.2's precondition for logging a shipment document — same rule as a
     * PI/invoice: you can't ship a {@code DRAFT}, so the PO must be {@code
     * GENERATED}/{@code SENT}. A distinct code ({@code PO_NOT_READY_FOR_SHIPMENT})
     * so the UI can say specifically why. Never exposes the {@code
     * PurchaseOrder}/{@code PurchaseOrderStatus}.
     */
    @Transactional(readOnly = true)
    public void assertOwnPurchaseOrderReadyForShipment(UUID id) {
        assertFinalised(id, ErrorCode.PO_NOT_READY_FOR_SHIPMENT, "a shipment");
    }

    private void assertFinalised(UUID id, ErrorCode errorCode, String documentNoun) {
        PurchaseOrder purchaseOrder = findOwnPurchaseOrder(id);
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.GENERATED
                && purchaseOrder.getStatus() != PurchaseOrderStatus.SENT) {
            throw new ConflictException(errorCode,
                "Purchase order is not ready for " + documentNoun + " in status " + purchaseOrder.getStatus());
        }
    }

    /**
     * Story 5.3's cross-module surface: the PO leg of a variance comparison
     * — supplier, currency, generation date, and each line's snapshotted
     * SKU/quantity/unit-price. Read-only; never exposes the {@code
     * PurchaseOrder}/{@code PurchaseOrderLine} entities themselves, only the
     * narrow {@link PurchaseOrderReconciliationView} the comparison reads.
     * The prices are the Feature 4 snapshot, deliberately — reconciliation
     * compares a PI against the price the PO actually carried, never a fresh
     * re-resolve (see {@code PurchaseOrderLine}'s price-snapshot Javadoc).
     */
    @Transactional(readOnly = true)
    public PurchaseOrderReconciliationView getReconciliationView(UUID id) {
        PurchaseOrder purchaseOrder = findOwnPurchaseOrder(id);

        List<PurchaseOrderLine> lines = purchaseOrderLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrder.getId()))
            .sorted(Comparator.comparingInt(PurchaseOrderLine::getLineNumber))
            .toList();

        String currency = lines.stream()
            .map(PurchaseOrderLine::getUnitPrice)
            .filter(unitPrice -> unitPrice != null)
            .map(unitPrice -> unitPrice.currency())
            .findFirst()
            .orElse(null);

        LocalDate generationDate = purchaseOrder.getGeneratedAt() == null
            ? null
            : LocalDate.ofInstant(purchaseOrder.getGeneratedAt(), ZoneOffset.UTC);

        List<PurchaseOrderReconciliationLine> viewLines = lines.stream()
            .map(line -> new PurchaseOrderReconciliationLine(
                line.getSkuId(),
                line.getQuantity(),
                line.getUnitPrice() == null ? null : line.getUnitPrice().amount(),
                Boolean.TRUE.equals(line.getPriceFound())))
            .toList();

        return new PurchaseOrderReconciliationView(
            purchaseOrder.getId(), purchaseOrder.getSupplierId(), currency, generationDate, viewLines);
    }

    /**
     * Story 5.5's cross-module surface: who raised this PO, for the
     * self-approval segregation-of-duties check (the PO creator must not be
     * able to sign off a price increase on their own order). Returns just the
     * {@code users.id} reference, never the {@code PurchaseOrder} itself —
     * same minimal-contract discipline as the other cross-module methods here.
     */
    @Transactional(readOnly = true)
    public UUID getCreatedBy(UUID id) {
        return findOwnPurchaseOrder(id).getCreatedBy();
    }

    /**
     * Story 6.3's cross-module surface: the PO's display reference and its
     * supplier, for the payment queue's rows. Never exposes the {@code
     * PurchaseOrder} itself — same minimal-contract discipline as {@link
     * #getReconciliationView}/{@link #getCreatedBy}.
     */
    @Transactional(readOnly = true)
    public PurchaseOrderSummary getSummary(UUID id) {
        PurchaseOrder purchaseOrder = findOwnPurchaseOrder(id);
        return new PurchaseOrderSummary(purchaseOrder.getId(), purchaseOrder.getPoNumber(), purchaseOrder.getSupplierId());
    }

    /** Package-visible: reused by {@link PurchaseOrderLineService} so both services share one ownership check. */
    PurchaseOrder findOwnPurchaseOrder(UUID id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Purchase order not found"));
        TenantGuard.assertOwned(purchaseOrder);
        return purchaseOrder;
    }

    /**
     * The status guard (Story 4.4's scope item 6): every mutation is DRAFT-only.
     * {@code PO_NOT_EDITABLE} rather than a silent no-op, so a caller
     * attempting to edit a GENERATED/SENT/CANCELLED PO gets a clear,
     * stable reason.
     */
    static void assertEditable(PurchaseOrder purchaseOrder) {
        if (!purchaseOrder.isEditable()) {
            throw new ConflictException(ErrorCode.PO_NOT_EDITABLE,
                "Purchase order is not editable in status " + purchaseOrder.getStatus());
        }
    }

    /** Package-visible: reused by {@link PurchaseOrderLineService}/{@code PurchaseOrderGenerationService}/{@code PurchaseOrderSendService} to return the same full representation after a mutation. */
    PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder) {
        List<PurchaseOrderLineResponse> lines = purchaseOrderLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrder.getId()))
            .sorted(Comparator.comparingInt(PurchaseOrderLine::getLineNumber))
            .map(PurchaseOrderService::toLineResponse)
            .toList();

        Optional<PurchaseOrderSend> latestSend = purchaseOrderSendRepository.findAll().stream()
            .filter(send -> send.getPurchaseOrderId().equals(purchaseOrder.getId()))
            .max(Comparator.comparing(PurchaseOrderSend::getSentAt));

        return new PurchaseOrderResponse(
            purchaseOrder.getId(),
            purchaseOrder.getSupplierId(),
            purchaseOrder.getPoNumber(),
            purchaseOrder.getStatus(),
            purchaseOrder.getRequestedEtd(),
            purchaseOrder.getCreatedBy(),
            purchaseOrder.getOrderTotal(),
            purchaseOrder.getDeposit(),
            purchaseOrder.getBalance(),
            lines,
            purchaseOrder.getCreatedAt(),
            purchaseOrder.getUpdatedAt(),
            purchaseOrder.getGeneratedBy(),
            purchaseOrder.getGeneratedAt(),
            latestSend.map(PurchaseOrderSend::getSentBy).orElse(null),
            latestSend.map(PurchaseOrderSend::getSentAt).orElse(null));
    }

    private static PurchaseOrderLineResponse toLineResponse(PurchaseOrderLine line) {
        return new PurchaseOrderLineResponse(
            line.getId(),
            line.getSkuId(),
            line.getLineNumber(),
            line.getQuantity(),
            line.getUnitPrice(),
            line.getAppliedTierThreshold(),
            line.getLineTotal(),
            line.getPriceFound(),
            line.getPricedAsOfDate(),
            line.getCartonValid(),
            line.getAdjustedQuantity(),
            line.getCreatedAt(),
            line.getUpdatedAt());
    }
}
