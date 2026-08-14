package com.shvoy.reconciliation.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The post-log reconciliation seam (Story 5.2, scope item 4) — a stub until
 * 5.3 fills in the actual comparison/variance logic. Deliberately called
 * only after {@code ProformaInvoiceRecordingService#recordPi}'s transaction
 * has already committed (see {@code ProformaInvoiceService#log}), so a
 * future failure in here can never lose an already-logged PI, and the
 * caller wraps this call so this stub's success is never a precondition of
 * the logging endpoint's own success.
 */
@Service
public class ReconciliationTriggerService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTriggerService.class);

    public void onPiLogged(UUID proformaInvoiceId) {
        log.info("PI {} logged; reconciliation comparison not yet implemented (Story 5.3)", proformaInvoiceId);
    }
}
