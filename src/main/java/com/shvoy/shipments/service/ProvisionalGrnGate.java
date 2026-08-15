package com.shvoy.shipments.service;

import org.springframework.stereotype.Component;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;

/**
 * The provisional-GRN document gate (Story 7.4) — the <strong>one</strong>, and
 * deliberately isolated, place the "which documents are mandatory" rule lives,
 * so <strong>Product Owner question #1</strong> is a one-line change (same
 * isolation discipline as the tolerance boundary, carton rule, and terms
 * snapshot).
 *
 * <p>It composes two parts:
 * <ul>
 *   <li>Story 7.3's <strong>certain</strong> rule — the consignment's own
 *       packing list is logged ({@link ShipmentConsignment#isReceiptEligible()});
 *       a sibling's never counts.</li>
 *   <li>The BL requirement — the shipment has a BL reference <em>and</em> date
 *       (the date is also the {@code BL} payment anchor).</li>
 * </ul>
 *
 * <p><strong>The inspection report is non-blocking</strong> — the stated lean
 * pending PO answer #1 (an inspection can be pending while goods ship). Making
 * it mandatory is the one-line change marked below.
 *
 * <p>Failures name the missing document so the UI can say "awaiting packing
 * list", not just "no".
 */
@Component
class ProvisionalGrnGate {

    void assertEligible(Shipment shipment, ShipmentConsignment consignment) {
        if (!consignment.isReceiptEligible()) {
            throw new ConflictException(ErrorCode.CONSIGNMENT_NOT_RECEIPT_ELIGIBLE,
                "Cannot create a provisional GRN — awaiting this consignment's own packing list");
        }
        if (shipment.getBlReference() == null || shipment.getBlDate() == null) {
            throw new ConflictException(ErrorCode.CONSIGNMENT_NOT_RECEIPT_ELIGIBLE,
                "Cannot create a provisional GRN — awaiting the Bill of Lading (reference and date)");
        }
        // PO question #1 flip point: to make the inspection report mandatory too, add here —
        //   if (consignment.getInspectionReportReference() == null) throw new ConflictException(...);
        // Built against the stated lean (inspection recorded but non-blocking).
    }

    boolean isEligible(Shipment shipment, ShipmentConsignment consignment) {
        try {
            assertEligible(shipment, consignment);
            return true;
        } catch (ConflictException e) {
            return false;
        }
    }
}
