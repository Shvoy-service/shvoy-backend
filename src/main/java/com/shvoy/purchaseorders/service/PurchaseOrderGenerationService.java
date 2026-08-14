package com.shvoy.purchaseorders.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.UnitPrice;
import com.shvoy.purchaseorders.document.PurchaseOrderDocumentData;
import com.shvoy.purchaseorders.document.PurchaseOrderDocumentRenderer;
import com.shvoy.purchaseorders.domain.PurchaseOrder;
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;
import com.shvoy.purchaseorders.domain.PurchaseOrderPriceOverride;
import com.shvoy.purchaseorders.domain.PurchaseOrderPriceOverrideLine;
import com.shvoy.purchaseorders.dto.GeneratePurchaseOrderRequest;
import com.shvoy.purchaseorders.dto.PurchaseOrderResponse;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderPriceOverrideLineRepository;
import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.dto.SkuSummary;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.service.PriceResolutionService;
import com.shvoy.suppliers.service.SkuService;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Story 4.6: finalises a draft PO into a durable, customer-facing PDF —
 * the last step of the workflow 4.4 (draft management), 4.5 (the expired-
 * price gate), and 4.3 (totals) all build up to.
 *
 * {@link #generate} checks its own two new preconditions (at least one
 * line, a requested ETD set) alongside reusing every other story's own
 * guard rather than re-implementing it: {@link PurchaseOrderService#assertEditable}
 * for the DRAFT-only status check, {@link PurchaseOrderFinalisationGateService}
 * for the expired-price gate. Once the gate passes, every line is
 * re-resolved and re-snapshotted as of **today** (the generation date, not
 * the draft date — a PO drafted days ago should reflect the price valid
 * now, see 4.2's own note on this), totals are recomputed once from the
 * final snapshot, and the whole thing is rendered to PDF and stored in S3
 * before the PO transitions to {@code GENERATED}. Prices are then locked —
 * not by anything new here, but because {@link PurchaseOrderService#assertEditable}
 * already blocks every mutation path once the status isn't {@code DRAFT}.
 */
@Service
public class PurchaseOrderGenerationService {

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PurchaseOrderPriceOverrideLineRepository overrideLineRepository;
    private final PurchaseOrderFinalisationGateService finalisationGateService;
    private final PurchaseOrderTotalsService totalsService;
    private final PriceResolutionService priceResolutionService;
    private final SupplierService supplierService;
    private final SkuService skuService;
    private final PurchaseOrderDocumentRenderer documentRenderer;
    private final S3Client s3Client;
    private final String documentsBucket;

    PurchaseOrderGenerationService(PurchaseOrderService purchaseOrderService,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            PurchaseOrderPriceOverrideLineRepository overrideLineRepository,
            PurchaseOrderFinalisationGateService finalisationGateService,
            PurchaseOrderTotalsService totalsService,
            PriceResolutionService priceResolutionService,
            SupplierService supplierService,
            SkuService skuService,
            PurchaseOrderDocumentRenderer documentRenderer,
            S3Client s3Client,
            @Value("${aws.s3.documents-bucket}") String documentsBucket) {
        this.purchaseOrderService = purchaseOrderService;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.overrideLineRepository = overrideLineRepository;
        this.finalisationGateService = finalisationGateService;
        this.totalsService = totalsService;
        this.priceResolutionService = priceResolutionService;
        this.supplierService = supplierService;
        this.skuService = skuService;
        this.documentRenderer = documentRenderer;
        this.s3Client = s3Client;
        this.documentsBucket = documentsBucket;
    }

    @Transactional
    public PurchaseOrderResponse generate(UUID purchaseOrderId, GeneratePurchaseOrderRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId);
        PurchaseOrderService.assertEditable(purchaseOrder);

        List<PurchaseOrderLine> lines = purchaseOrderLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();
        assertReadyToGenerate(purchaseOrder, lines);

        Optional<PurchaseOrderPriceOverride> override = finalisationGateService.checkFinalisationGate(
            purchaseOrderId, request == null ? null : request.override());
        Map<UUID, UnitPrice> overriddenPrices = loadOverriddenPrices(override);

        LocalDate today = LocalDate.now();
        for (PurchaseOrderLine line : lines) {
            PriceResolutionResult resolved = priceResolutionService.resolve(
                purchaseOrder.getSupplierId(), line.getSkuId(), line.getQuantity(), today);
            if (!resolved.priceFound()) {
                resolved = withOverriddenPrice(resolved, overriddenPrices.get(line.getId()));
            }
            line.applyPriceResolution(resolved);
            purchaseOrderLineRepository.save(line);
        }
        purchaseOrder = totalsService.recompute(purchaseOrderId);

        PurchaseOrderDocumentData documentData = buildDocumentData(purchaseOrder, lines);
        byte[] pdf = documentRenderer.render(documentData);
        String s3Key = storeDocument(purchaseOrder, pdf);

        purchaseOrder.applyGeneration(CurrentUserContext.get(), s3Key);
        return purchaseOrderService.toResponse(purchaseOrder);
    }

    @Transactional(readOnly = true)
    public byte[] getDocument(UUID purchaseOrderId) {
        PurchaseOrder purchaseOrder = purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId);
        if (purchaseOrder.getDocumentS3Key() == null) {
            throw new NotFoundException("Purchase order document not found");
        }
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(documentsBucket).key(purchaseOrder.getDocumentS3Key()).build());
        return object.asByteArray();
    }

    /**
     * The two preconditions this story owns (line count, ETD set) — the
     * expired-price precondition is 4.5's gate, called separately; "all
     * lines have a resolved price" isn't a separate check here, since
     * re-resolving every line right after this (with the gate already
     * passed) guarantees it.
     */
    private static void assertReadyToGenerate(PurchaseOrder purchaseOrder, List<PurchaseOrderLine> lines) {
        if (lines.isEmpty()) {
            throw new ConflictException(ErrorCode.PO_NOT_READY_TO_GENERATE, "Purchase order has no lines");
        }
        if (purchaseOrder.getRequestedEtd() == null) {
            throw new ConflictException(ErrorCode.PO_NOT_READY_TO_GENERATE, "Purchase order has no requested ETD set");
        }
    }

    private Map<UUID, UnitPrice> loadOverriddenPrices(Optional<PurchaseOrderPriceOverride> override) {
        if (override.isEmpty()) {
            return Map.of();
        }
        return overrideLineRepository.findAll().stream()
            .filter(overrideLine -> overrideLine.getOverrideId().equals(override.get().getId()))
            .collect(Collectors.toMap(
                PurchaseOrderPriceOverrideLine::getPurchaseOrderLineId,
                PurchaseOrderPriceOverrideLine::getManualPrice));
    }

    /**
     * Substitutes the override's manual price for a line 3.8 still can't
     * resolve (overriding doesn't change the underlying {@code SkuPrice}
     * data, so re-resolution finds nothing here either — see the class
     * Javadoc) while keeping everything else from the fresh resolution
     * (carton validity/adjusted quantity are independent of price, and
     * still worth carrying accurately). No tier applies to a manually
     * supplied price.
     */
    private static PriceResolutionResult withOverriddenPrice(PriceResolutionResult resolved, UnitPrice manualPrice) {
        return new PriceResolutionResult(true, null, manualPrice, null,
            resolved.asOfDate(), resolved.cartonValid(), resolved.adjustedQuantity(), true);
    }

    private PurchaseOrderDocumentData buildDocumentData(PurchaseOrder purchaseOrder, List<PurchaseOrderLine> lines) {
        SupplierSummary supplier = supplierService.getSummary(purchaseOrder.getSupplierId());
        List<PurchaseOrderDocumentData.LineItem> lineItems = lines.stream()
            .sorted(Comparator.comparingInt(PurchaseOrderLine::getLineNumber))
            .map(line -> {
                SkuSummary sku = skuService.getSummary(purchaseOrder.getSupplierId(), line.getSkuId());
                return new PurchaseOrderDocumentData.LineItem(
                    sku.code(), sku.description(), line.getQuantity(),
                    line.getUnitPrice(), line.getAppliedTierThreshold(), line.getLineTotal());
            })
            .toList();

        return new PurchaseOrderDocumentData(
            purchaseOrder.getPoNumber(),
            supplier.name(),
            supplier.country(),
            supplier.contactEmail(),
            purchaseOrder.getRequestedEtd(),
            lineItems,
            purchaseOrder.getOrderTotal(),
            purchaseOrder.getDeposit(),
            purchaseOrder.getBalance(),
            Instant.now());
    }

    /** Same key convention as {@code PriceFileUploadService#storeInS3}. */
    private String storeDocument(PurchaseOrder purchaseOrder, byte[] pdf) {
        String key = "purchase-order-documents/%s/%s/%s-%s.pdf".formatted(
            TenantContext.get(), purchaseOrder.getId(), UUID.randomUUID(), purchaseOrder.getPoNumber());
        s3Client.putObject(
            PutObjectRequest.builder().bucket(documentsBucket).key(key).build(),
            RequestBody.fromBytes(pdf));
        return key;
    }
}
