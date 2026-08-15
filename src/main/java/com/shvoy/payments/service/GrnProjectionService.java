package com.shvoy.payments.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.payments.domain.GrnProjectionLine;
import com.shvoy.payments.event.ProvisionalGoodsReceiptEvent;
import com.shvoy.payments.repository.GrnProjectionLineRepository;

/**
 * Maintains the payments-local projection of goods-received quantities (Story
 * 6.5) from the {@link ProvisionalGoodsReceiptEvent} that {@code shipments}
 * publishes (7.4). Payments can't pull the GRN from {@code shipments} (the
 * module graph would cycle), so it keeps this projection and the three-way match
 * reads the GRN leg from it. Full-replaced per consignment on each (re-)publish,
 * so a GRN amendment overwrites the prior quantities.
 */
@Service
class GrnProjectionService {

    private final GrnProjectionLineRepository grnProjectionLineRepository;

    GrnProjectionService(GrnProjectionLineRepository grnProjectionLineRepository) {
        this.grnProjectionLineRepository = grnProjectionLineRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void project(ProvisionalGoodsReceiptEvent event) {
        grnProjectionLineRepository.findAll().stream()
            .filter(line -> line.getConsignmentId().equals(event.consignmentId()))
            .forEach(grnProjectionLineRepository::delete);
        event.receivedLines().forEach(line -> grnProjectionLineRepository.save(
            new GrnProjectionLine(event.purchaseOrderId(), event.consignmentId(), line.skuId(), line.receivedQuantity())));
    }
}
