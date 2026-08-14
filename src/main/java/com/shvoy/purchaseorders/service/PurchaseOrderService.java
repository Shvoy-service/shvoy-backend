package com.shvoy.purchaseorders.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

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
import com.shvoy.purchaseorders.domain.PurchaseOrderStatus;
import com.shvoy.purchaseorders.dto.CreatePurchaseOrderRequest;
import com.shvoy.purchaseorders.dto.PurchaseOrderLineResponse;
import com.shvoy.purchaseorders.dto.PurchaseOrderResponse;
import com.shvoy.purchaseorders.dto.UpdateRequestedEtdRequest;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderRepository;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Story 4.4: creation, reads, ETD, and cancellation of a draft PO — the
 * line-mutation half of the story is {@link PurchaseOrderLineService},
 * which reuses {@link #findOwnPurchaseOrder}/{@link #assertEditable}/
 * {@link #toResponse} from here rather than duplicating the ownership/
 * status-guard/assembly logic.
 */
@Service
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final SupplierService supplierService;
    private final PoNumberGenerator poNumberGenerator;

    PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            SupplierService supplierService,
            PoNumberGenerator poNumberGenerator) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
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

    /** Package-visible: reused by {@link PurchaseOrderLineService} to return the same full representation after a line mutation. */
    PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder) {
        List<PurchaseOrderLineResponse> lines = purchaseOrderLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrder.getId()))
            .sorted(Comparator.comparingInt(PurchaseOrderLine::getLineNumber))
            .map(PurchaseOrderService::toLineResponse)
            .toList();

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
            purchaseOrder.getUpdatedAt());
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
