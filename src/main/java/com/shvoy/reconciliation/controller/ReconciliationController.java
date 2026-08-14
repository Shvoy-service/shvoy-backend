package com.shvoy.reconciliation.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.reconciliation.dto.ReconciliationResponse;
import com.shvoy.reconciliation.service.ReconciliationService;

/**
 * Story 5.3 — read the comparison recorded for a logged PI. The comparison
 * itself runs automatically when the PI is logged (5.2's post-log seam), so
 * there's no "run reconciliation" endpoint here; this only exposes the stored
 * result (for Screen 4's comparison table). Open to any authenticated company
 * user, same as every other read in this codebase — reading a comparison
 * isn't a mutation. Tolerance/outcome/approval endpoints belong to 5.4+.
 */
@RestController
class ReconciliationController {

    private final ReconciliationService reconciliationService;

    ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/api/proforma-invoices/{piId}/reconciliation")
    ReconciliationResponse getForProformaInvoice(@PathVariable UUID piId) {
        return reconciliationService.getForProformaInvoice(piId);
    }
}
