package com.shvoy.payments.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.domain.PaymentType;
import com.shvoy.payments.dto.PaymentQueueResponse;
import com.shvoy.payments.dto.PaymentQueueRow;
import com.shvoy.payments.dto.PaymentStatsResponse;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.purchaseorders.dto.PurchaseOrderSummary;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.service.SupplierService;

/**
 * The read side of the payment queue (Story 6.3) — "what do we owe, to whom,
 * and when." Pure read: no state, no mutation (Pay/Hold is 6.8, match status
 * 6.5). Filters/sorts in Java over {@code findAll()}, same convention as the
 * rest of the codebase; capped and paged so a busy company never gets an
 * unbounded list.
 *
 * <p><strong>Two boundaries pinned here (and in docs/CONTRACT.md), because
 * they render as red flags and counts a finance user acts on:</strong>
 * <ul>
 *   <li>A payment is <strong>overdue</strong> iff its due date is <em>strictly
 *       before</em> today and it isn't {@code PAID} — due <em>today</em> is not
 *       overdue. Derived at read time, never stored (same principle as a
 *       price's in-date/expired status).</li>
 *   <li><strong>Due within 5 days</strong> is {@code [today, today+5]},
 *       <em>inclusive</em> of day 5.</li>
 * </ul>
 */
@Service
public class PaymentQueueService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int DUE_SOON_WINDOW_DAYS = 5;

    /** Dated payments first, soonest due first; undated (awaiting anchor) grouped after — their clock hasn't started. */
    private static final Comparator<Payment> BY_DUE_DATE =
        Comparator.comparing(Payment::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Payment::getCreatedAt);

    private final PaymentRepository paymentRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final SupplierService supplierService;

    PaymentQueueService(PaymentRepository paymentRepository, PurchaseOrderService purchaseOrderService,
            SupplierService supplierService) {
        this.paymentRepository = paymentRepository;
        this.supplierService = supplierService;
        this.purchaseOrderService = purchaseOrderService;
    }

    /**
     * The queue. When {@code status} is null the default operational view
     * applies — everything not yet {@code PAID}; a supplied status overrides
     * it. {@code type}, the due-date range, and {@code overdueOnly} narrow
     * further. A due-date range excludes undated payments (they have no date to
     * match).
     */
    @Transactional(readOnly = true)
    public PaymentQueueResponse getQueue(PaymentStatus status, PaymentType type, LocalDate dueFrom, LocalDate dueTo,
            boolean overdueOnly, int page, int size) {
        LocalDate today = LocalDate.now();
        int effectivePage = Math.max(0, page);
        int effectiveSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        List<Payment> matching = paymentRepository.findAll().stream()
            .filter(p -> status != null ? p.getStatus() == status : p.getStatus() != PaymentStatus.PAID)
            .filter(p -> type == null || p.getType() == type)
            .filter(p -> matchesDueRange(p, dueFrom, dueTo))
            .filter(p -> !overdueOnly || isOverdue(p, today))
            .sorted(BY_DUE_DATE)
            .toList();

        List<Payment> pageContent = matching.stream()
            .skip((long) effectivePage * effectiveSize)
            .limit(effectiveSize)
            .toList();

        return new PaymentQueueResponse(toRows(pageContent, today), effectivePage, effectiveSize, matching.size());
    }

    @Transactional(readOnly = true)
    public PaymentStatsResponse getStats() {
        LocalDate today = LocalDate.now();
        LocalDate dueSoonCutoff = today.plusDays(DUE_SOON_WINDOW_DAYS);
        List<Payment> all = paymentRepository.findAll();

        long overdue = all.stream().filter(p -> isOverdue(p, today)).count();
        long dueSoon = all.stream()
            .filter(p -> p.getStatus() != PaymentStatus.PAID)
            .filter(p -> p.getDueDate() != null)
            .filter(p -> !p.getDueDate().isBefore(today) && !p.getDueDate().isAfter(dueSoonCutoff))
            .count();
        return new PaymentStatsResponse(overdue, dueSoon);
    }

    // --- derivations ---

    /** Strictly before today, and not paid — see the class Javadoc. */
    private static boolean isOverdue(Payment payment, LocalDate today) {
        return payment.getDueDate() != null
            && payment.getDueDate().isBefore(today)
            && payment.getStatus() != PaymentStatus.PAID;
    }

    private static boolean matchesDueRange(Payment payment, LocalDate dueFrom, LocalDate dueTo) {
        if (dueFrom == null && dueTo == null) {
            return true;
        }
        LocalDate dueDate = payment.getDueDate();
        return dueDate != null
            && (dueFrom == null || !dueDate.isBefore(dueFrom))
            && (dueTo == null || !dueDate.isAfter(dueTo));
    }

    // --- row assembly (PO reference + supplier name, batched per page) ---

    private List<PaymentQueueRow> toRows(List<Payment> payments, LocalDate today) {
        Map<UUID, PurchaseOrderSummary> poById = payments.stream()
            .map(Payment::getPurchaseOrderId)
            .distinct()
            .collect(Collectors.toMap(Function.identity(), purchaseOrderService::getSummary));

        Set<UUID> supplierIds = poById.values().stream().map(PurchaseOrderSummary::supplierId).collect(Collectors.toSet());
        Map<UUID, SupplierSummary> supplierById = supplierIds.stream()
            .collect(Collectors.toMap(Function.identity(), supplierService::getSummary));

        return payments.stream().map(payment -> {
            PurchaseOrderSummary po = poById.get(payment.getPurchaseOrderId());
            SupplierSummary supplier = po == null ? null : supplierById.get(po.supplierId());
            return new PaymentQueueRow(
                payment.getId(),
                payment.getPurchaseOrderId(),
                po == null ? null : po.poNumber(),
                po == null ? null : po.supplierId(),
                supplier == null ? null : supplier.name(),
                payment.getType(),
                payment.getAmount(),
                payment.getDueDate(),
                payment.getStatus(),
                isOverdue(payment, today),
                payment.getDueDate() == null);
        }).toList();
    }
}
