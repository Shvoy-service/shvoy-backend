package com.shvoy.payments.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.payments.domain.PaymentAuditEvent;
import com.shvoy.payments.domain.PaymentAuditEventType;
import com.shvoy.payments.repository.PaymentAuditEventRepository;

/**
 * Appends to and reads the immutable payment audit trail (Story 6.2). Append
 * and read only — there is intentionally no update or delete method here or on
 * the repository (see {@code PaymentAuditEventRepository}).
 */
@Service
public class PaymentAuditService {

    private final PaymentAuditEventRepository auditEventRepository;

    PaymentAuditService(PaymentAuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /** Runs in the caller's transaction, so the audit entry commits together with the change it records. */
    public void record(UUID paymentId, UUID purchaseOrderId, PaymentAuditEventType eventType, String detail) {
        auditEventRepository.save(new PaymentAuditEvent(paymentId, purchaseOrderId, eventType, detail));
    }

    @Transactional(readOnly = true)
    public List<PaymentAuditEvent> trailFor(UUID paymentId) {
        return auditEventRepository.findAll().stream()
            .filter(event -> event.getPaymentId().equals(paymentId))
            .sorted(Comparator.comparing(PaymentAuditEvent::getCreatedAt))
            .toList();
    }
}
