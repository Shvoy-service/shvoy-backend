package com.shvoy.payments.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.Money;
import com.shvoy.payments.domain.Invoice;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentAuditEventType;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.domain.GrnProjectionLine;
import com.shvoy.payments.dto.CreditMatchResult;
import com.shvoy.payments.repository.GrnProjectionLineRepository;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.payments.service.ThreeWayMatchEvaluator.MatchInputs;
import com.shvoy.payments.service.ThreeWayMatchEvaluator.MatchVerdict;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationLine;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationView;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.dto.ConfirmedProformaInvoiceLine;
import com.shvoy.reconciliation.dto.ConfirmedProformaInvoiceView;
import com.shvoy.reconciliation.service.ProformaInvoiceMatchService;

/**
 * The three-way match (Story 6.5) — the pre-payment control the product exists
 * for. Before a balance can be marked ready-to-pay, the PO, confirmed PI, and
 * goods receipt must agree with the final invoice. A pass makes the balance
 * {@code READY_TO_PAY}; a fail {@code BLOCKED} — <strong>block by default</strong>,
 * never warn-and-allow.
 *
 * <p>{@link #evaluate} is idempotent and deterministic, driven event-driven by
 * {@link MatchTriggerListener} whenever a leg arrives or changes. It gathers the
 * legs each run (PO pulled from {@code purchaseorders}, confirmed PI pulled from
 * {@code reconciliation}, GRN read from the local projection, invoice + credit
 * local) and applies the pure {@link ThreeWayMatchEvaluator}. It owns only the
 * automatic states — a payment a human parked ({@code ON_HOLD}) or released
 * ({@code PAID}) is left untouched.
 *
 * <p>The <strong>verdict</strong> is this story's job; the decision to pay
 * (Pay/Hold) is 6.6/6.8's — a clean boundary.
 */
@Service
public class ThreeWayMatchService {

    private final PaymentRepository paymentRepository;
    private final PaymentAuditService paymentAuditService;
    private final PaymentGatePolicy gatePolicy;
    private final PurchaseOrderService purchaseOrderService;
    private final ProformaInvoiceMatchService proformaInvoiceMatchService;
    private final GrnProjectionLineRepository grnProjectionLineRepository;
    private final InvoiceRepository invoiceRepository;
    private final CreditLedgerService creditLedgerService;

    ThreeWayMatchService(PaymentRepository paymentRepository, PaymentAuditService paymentAuditService,
            PaymentGatePolicy gatePolicy, PurchaseOrderService purchaseOrderService,
            ProformaInvoiceMatchService proformaInvoiceMatchService,
            GrnProjectionLineRepository grnProjectionLineRepository, InvoiceRepository invoiceRepository,
            CreditLedgerService creditLedgerService) {
        this.paymentRepository = paymentRepository;
        this.paymentAuditService = paymentAuditService;
        this.gatePolicy = gatePolicy;
        this.purchaseOrderService = purchaseOrderService;
        this.proformaInvoiceMatchService = proformaInvoiceMatchService;
        this.grnProjectionLineRepository = grnProjectionLineRepository;
        this.invoiceRepository = invoiceRepository;
        this.creditLedgerService = creditLedgerService;
    }

    @Transactional
    public void evaluate(UUID purchaseOrderId) {
        List<Payment> payments = paymentRepository.findAll().stream()
            .filter(payment -> payment.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();
        for (Payment payment : payments) {
            if (!payment.isMatchMutable()) {
                continue; // a human PAID/ON_HOLD decision stands — the match never overrides it
            }
            if (!gatePolicy.requiresThreeWayMatch(payment.getType())) {
                promoteDeposit(purchaseOrderId, payment);
            } else {
                evaluateBalance(purchaseOrderId, payment);
            }
        }
    }

    /** Per the per-type gate policy: a deposit is payable without the match (its gate is PO generation). */
    private void promoteDeposit(UUID purchaseOrderId, Payment deposit) {
        if (deposit.getStatus() == PaymentStatus.READY_TO_PAY) {
            return;
        }
        deposit.markPayableWithoutMatch();
        paymentRepository.save(deposit);
        paymentAuditService.record(deposit.getId(), purchaseOrderId, PaymentAuditEventType.DEPOSIT_PAYABLE,
            "Deposit payable without the three-way match (per-type gate policy)");
    }

    private void evaluateBalance(UUID purchaseOrderId, Payment balance) {
        Optional<ConfirmedProformaInvoiceView> pi = proformaInvoiceMatchService.getConfirmedForMatch(purchaseOrderId);
        List<GrnProjectionLine> grn = grnProjectionLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();
        Optional<Invoice> invoice = invoiceRepository.findAll().stream()
            .filter(inv -> inv.getPurchaseOrderId().equals(purchaseOrderId) && inv.isActive())
            .findFirst();

        List<String> missing = new ArrayList<>();
        if (pi.isEmpty()) {
            missing.add("confirmed PI");
        }
        if (grn.isEmpty()) {
            missing.add("goods receipt");
        }
        if (invoice.isEmpty()) {
            missing.add("invoice");
        }
        if (!missing.isEmpty()) {
            // Missing legs are "awaiting X", not a mismatch — stay PENDING, honestly.
            balance.markAwaiting("Awaiting " + String.join(", ", missing));
            paymentRepository.save(balance);
            return;
        }

        PurchaseOrderReconciliationView po = purchaseOrderService.getReconciliationView(purchaseOrderId);
        Invoice inv = invoice.get();
        Money claimedCredit = inv.getClaimedCredit();

        UUID creditEntryToApply = null;
        boolean creditValid = true;
        String creditOutcome = null;
        if (claimedCredit != null) {
            CreditMatchResult result = creditLedgerService.checkClaim(purchaseOrderId, claimedCredit);
            if (result.matched()) {
                creditEntryToApply = result.matchedEntryId();
            } else if (!creditLedgerService.claimAlreadyAppliedToInvoice(purchaseOrderId, claimedCredit, inv.getId())) {
                creditValid = false;
                creditOutcome = result.outcome().name();
            }
        }

        MatchInputs inputs = new MatchInputs(
            po.lines().stream().collect(Collectors.toMap(
                PurchaseOrderReconciliationLine::skuId, PurchaseOrderReconciliationLine::quantity, Integer::sum)),
            pi.get().currency(),
            pi.get().lines().stream()
                .map(line -> new MatchInputs.PiLine(
                    line.skuId(), line.confirmedUnitPriceAmount(), line.confirmedQuantity()))
                .toList(),
            grn.stream().collect(Collectors.toMap(
                GrnProjectionLine::getSkuId, GrnProjectionLine::getReceivedQuantity, Integer::sum)),
            inv.getAmount(),
            claimedCredit,
            creditValid,
            creditOutcome);

        MatchVerdict verdict = ThreeWayMatchEvaluator.evaluate(inputs);
        if (verdict.passed()) {
            if (creditEntryToApply != null) {
                creditLedgerService.apply(creditEntryToApply, inv.getId());
            }
            if (balance.getStatus() != PaymentStatus.READY_TO_PAY) {
                balance.markMatchPassed();
                paymentRepository.save(balance);
                paymentAuditService.record(balance.getId(), purchaseOrderId, PaymentAuditEventType.MATCH_PASSED,
                    "Three-way match passed — READY_TO_PAY");
            }
        } else {
            boolean changed = balance.getStatus() != PaymentStatus.BLOCKED
                || !Objects.equals(balance.getMatchDetail(), verdict.detail());
            balance.markMatchBlocked(verdict.detail());
            paymentRepository.save(balance);
            if (changed) {
                paymentAuditService.record(balance.getId(), purchaseOrderId, PaymentAuditEventType.MATCH_BLOCKED,
                    verdict.detail());
            }
        }
    }
}
