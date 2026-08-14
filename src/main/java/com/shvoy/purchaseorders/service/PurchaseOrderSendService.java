package com.shvoy.purchaseorders.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.EmailAttachment;
import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;
import com.shvoy.ErrorCode;
import com.shvoy.purchaseorders.domain.PurchaseOrder;
import com.shvoy.purchaseorders.domain.PurchaseOrderSend;
import com.shvoy.purchaseorders.domain.PurchaseOrderStatus;
import com.shvoy.purchaseorders.dto.PurchaseOrderResponse;
import com.shvoy.purchaseorders.repository.PurchaseOrderRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderSendRepository;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Story 4.7: dispatches an already-{@link PurchaseOrderGenerationService
 * generated} PO to its supplier — the final step of Feature 4's lifecycle.
 * Sends the PDF 4.6 already produced (fetched via {@code
 * PurchaseOrderGenerationService#getDocument}, not re-fetched from S3
 * independently) — never re-prices or regenerates it, since the document is
 * locked the moment it's created.
 *
 * <b>Resend is allowed</b> (the story's recommended default, confirmed
 * here): a PO already {@code SENT} can be sent again — "please resend the
 * PO" is a normal real-world request — and each attempt, first or repeat,
 * appends its own {@link PurchaseOrderSend} audit row. The alternative
 * (block resend entirely, {@code PO_ALREADY_SENT}) was the simpler MVP
 * option the story offered instead; not chosen, since blocking a
 * legitimate resend request would just push the user to re-generate or
 * work around it some other way.
 *
 * Email delivery goes through {@link EmailSender} — see that interface's
 * Javadoc for why this is a shared seam with {@code InvitationService}
 * (2.3), not a PO-specific one.
 */
@Service
public class PurchaseOrderSendService {

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderSendRepository purchaseOrderSendRepository;
    private final PurchaseOrderGenerationService purchaseOrderGenerationService;
    private final SupplierService supplierService;
    private final EmailSender emailSender;

    PurchaseOrderSendService(PurchaseOrderService purchaseOrderService,
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderSendRepository purchaseOrderSendRepository,
            PurchaseOrderGenerationService purchaseOrderGenerationService,
            SupplierService supplierService,
            EmailSender emailSender) {
        this.purchaseOrderService = purchaseOrderService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderSendRepository = purchaseOrderSendRepository;
        this.purchaseOrderGenerationService = purchaseOrderGenerationService;
        this.supplierService = supplierService;
        this.emailSender = emailSender;
    }

    @Transactional
    public PurchaseOrderResponse send(UUID purchaseOrderId) {
        PurchaseOrder purchaseOrder = purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId);
        assertSendable(purchaseOrder);

        SupplierSummary supplier = supplierService.getSummary(purchaseOrder.getSupplierId());
        if (supplier.contactEmail() == null || supplier.contactEmail().isBlank()) {
            throw new ConflictException(ErrorCode.SUPPLIER_MISSING_CONTACT_EMAIL,
                "Supplier has no contact email on file");
        }

        byte[] document = purchaseOrderGenerationService.getDocument(purchaseOrderId);
        emailSender.send(new EmailMessage(
            supplier.contactEmail(),
            "Purchase Order " + purchaseOrder.getPoNumber(),
            "Please find attached Purchase Order " + purchaseOrder.getPoNumber() + " from " + supplier.name() + ".",
            new EmailAttachment(purchaseOrder.getPoNumber() + ".pdf", "application/pdf", document)));

        purchaseOrderSendRepository.save(new PurchaseOrderSend(
            purchaseOrderId, CurrentUserContext.get(), supplier.contactEmail(), purchaseOrder.getDocumentS3Key()));

        purchaseOrder.markSent();
        return purchaseOrderService.toResponse(purchaseOrderRepository.save(purchaseOrder));
    }

    private static void assertSendable(PurchaseOrder purchaseOrder) {
        PurchaseOrderStatus status = purchaseOrder.getStatus();
        if (status != PurchaseOrderStatus.GENERATED && status != PurchaseOrderStatus.SENT) {
            throw new ConflictException(ErrorCode.PO_NOT_SENDABLE,
                "Purchase order must be generated before it can be sent (current status: " + status + ")");
        }
    }
}
