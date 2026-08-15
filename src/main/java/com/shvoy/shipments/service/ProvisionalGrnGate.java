package com.shvoy.shipments.service;

import org.springframework.stereotype.Component;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.shipments.domain.GrnProvenance;
import com.shvoy.shipments.domain.InspectionOutcome;
import com.shvoy.shipments.domain.ReceiptStatus;
import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;

/**
 * The provisional-GRN gate (Story 7.4 revised) — the <strong>one</strong>,
 * deliberately isolated, place all the now-<em>confirmed</em> gating rules live.
 * It composes:
 * <ul>
 *   <li>the consignment's <strong>own packing list</strong> (7.3's rule) — always;</li>
 *   <li>the shipment's <strong>BL reference + date</strong> — always;</li>
 *   <li><strong>not in a rework hold</strong> — a {@code REWORK_REQUIRED}
 *       consignment has nothing to receive (goods stayed at the factory);</li>
 *   <li>if <strong>inspection-due</strong>: a resolved inspection ({@code PASS}
 *       or {@code FAIL}) must be logged — due-but-no-report blocks. If
 *       <strong>not due</strong>: no inspection requirement at all (the no-QC
 *       path).</li>
 * </ul>
 *
 * <p>Each block names its cause with a distinct stable code, so the UI can say
 * <em>why</em>, specifically. A {@code FAIL} inspection does <strong>not</strong>
 * block — it drives the GRN's {@code qc_failed} provenance (see {@link
 * #provenanceFor}), because the goods are real and on the water.
 */
@Component
class ProvisionalGrnGate {

    void assertEligible(Shipment shipment, ShipmentConsignment consignment) {
        if (consignment.getReceiptStatus() == ReceiptStatus.REWORK_REQUIRED) {
            throw new ConflictException(ErrorCode.CONSIGNMENT_IN_REWORK_HOLD,
                "Cannot create a provisional GRN — held at the factory for rework (nothing shipped yet)");
        }
        if (!consignment.isReceiptEligible()) {
            throw new ConflictException(ErrorCode.CONSIGNMENT_NOT_RECEIPT_ELIGIBLE,
                "Cannot create a provisional GRN — awaiting this consignment's own packing list");
        }
        if (shipment.getBlReference() == null || shipment.getBlDate() == null) {
            throw new ConflictException(ErrorCode.CONSIGNMENT_NOT_RECEIPT_ELIGIBLE,
                "Cannot create a provisional GRN — awaiting the Bill of Lading (reference and date)");
        }
        if (consignment.isInspectionDue() && !inspectionResolved(consignment)) {
            throw new ConflictException(ErrorCode.CONSIGNMENT_INSPECTION_PENDING,
                "Cannot create a provisional GRN — an inspection is due but no PASS/FAIL report is logged yet");
        }
    }

    /**
     * The provenance the GRN will carry, given it passes the gate. Keyed off the
     * inspection <em>outcome</em>, not the due flag (a failure is a failure
     * whether or not it was strictly due): a latest {@code FAIL} → {@code
     * QC_FAILED} (the GRN still creates), a latest {@code PASS} → {@code CLEAN},
     * and no inspection outcome at all → {@code INSPECTION_NOT_DUE} (created
     * legitimately without an inspection — the not-due path). {@code
     * REWORK_REQUIRED} can't reach here; the gate blocks it as a hold.
     */
    GrnProvenance provenanceFor(ShipmentConsignment consignment) {
        InspectionOutcome latest = consignment.latestInspectionOutcome();
        if (latest == InspectionOutcome.FAIL) {
            return GrnProvenance.QC_FAILED;
        }
        if (latest == InspectionOutcome.PASS) {
            return GrnProvenance.CLEAN;
        }
        return GrnProvenance.INSPECTION_NOT_DUE;
    }

    /** A due inspection is "resolved" once a PASS or FAIL is on record (REWORK is caught by the hold check above). */
    private boolean inspectionResolved(ShipmentConsignment consignment) {
        InspectionOutcome latest = consignment.latestInspectionOutcome();
        return latest == InspectionOutcome.PASS || latest == InspectionOutcome.FAIL;
    }
}
