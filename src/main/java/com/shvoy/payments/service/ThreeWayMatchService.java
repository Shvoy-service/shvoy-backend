package com.shvoy.payments.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.Money;
import com.shvoy.UnitPrice;
import com.shvoy.payments.domain.Invoice;
import com.shvoy.payments.domain.InvoiceCoversType;
import com.shvoy.payments.domain.InvoiceMatchResult;
import com.shvoy.payments.domain.MatchPolicy;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentAuditEventType;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.domain.PaymentType;
import com.shvoy.payments.domain.GrnProjectionLine;
import com.shvoy.payments.dto.CreditMatchResult;
import com.shvoy.payments.repository.GrnProjectionLineRepository;
import com.shvoy.payments.repository.InvoiceCoveredLineRepository;
import com.shvoy.payments.repository.InvoiceMatchResultRepository;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.payments.service.InvoiceMatchEvaluator.Claim;
import com.shvoy.payments.service.InvoiceMatchEvaluator.Legs;
import com.shvoy.payments.service.InvoiceMatchEvaluator.Verdict;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationLine;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationView;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.dto.ConfirmedProformaInvoiceLine;
import com.shvoy.reconciliation.dto.ConfirmedProformaInvoiceView;
import com.shvoy.reconciliation.service.ProformaInvoiceMatchService;
import com.shvoy.suppliers.domain.PaymentTermsType;
import com.shvoy.suppliers.service.PaymentTermsService;

/**
 * The three-way match, rebuilt for the 1:many world (Story 6.5 re-spec) — the
 * pre-payment control the product exists for. <strong>Two dispatches that
 * compose:</strong> each active invoice matches against <em>what it declares it
 * covers</em> ({@code covers_type} → {@link InvoiceMatchEvaluator} strategy),
 * and the verdict's <em>consequence</em> depends on the supplier's {@code
 * terms_type} ({@link MatchConsequencePolicy}: per-PO payment gating for
 * deposit/balance &amp; zero-deposit; record-only feeding the statement view for
 * rolling). Block-by-default stands.
 *
 * <p>{@link #evaluate} is idempotent and deterministic, driven event-driven by
 * {@link MatchTriggerListener} whenever any leg arrives or changes — including a
 * new partial shipment's GRN, which can flip a waiting {@code BALANCE} invoice
 * to matchable. It gathers the legs once, evaluates the invoices in a stable
 * order accumulating matched value (so the {@link MatchRollupEvaluator} can
 * catch collective over-claim), persists a per-invoice {@link InvoiceMatchResult}
 * (the durable verdict the statement view and Finance read), then applies the
 * terms-type consequence. It owns only the automatic payment states — a payment
 * a human parked ({@code ON_HOLD}) or released ({@code PAID}) is left untouched.
 */
@Service
public class ThreeWayMatchService {

    private final PaymentRepository paymentRepository;
    private final PaymentAuditService paymentAuditService;
    private final MatchConsequencePolicy consequencePolicy;
    private final PurchaseOrderService purchaseOrderService;
    private final ProformaInvoiceMatchService proformaInvoiceMatchService;
    private final GrnProjectionLineRepository grnProjectionLineRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceCoveredLineRepository invoiceCoveredLineRepository;
    private final InvoiceMatchResultRepository invoiceMatchResultRepository;
    private final CreditLedgerService creditLedgerService;
    private final DiscrepancyCaseService discrepancyCaseService;
    private final PaymentTermsService paymentTermsService;

    ThreeWayMatchService(PaymentRepository paymentRepository, PaymentAuditService paymentAuditService,
            MatchConsequencePolicy consequencePolicy, PurchaseOrderService purchaseOrderService,
            ProformaInvoiceMatchService proformaInvoiceMatchService,
            GrnProjectionLineRepository grnProjectionLineRepository, InvoiceRepository invoiceRepository,
            InvoiceCoveredLineRepository invoiceCoveredLineRepository,
            InvoiceMatchResultRepository invoiceMatchResultRepository, CreditLedgerService creditLedgerService,
            DiscrepancyCaseService discrepancyCaseService, PaymentTermsService paymentTermsService) {
        this.paymentRepository = paymentRepository;
        this.paymentAuditService = paymentAuditService;
        this.consequencePolicy = consequencePolicy;
        this.purchaseOrderService = purchaseOrderService;
        this.proformaInvoiceMatchService = proformaInvoiceMatchService;
        this.grnProjectionLineRepository = grnProjectionLineRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceCoveredLineRepository = invoiceCoveredLineRepository;
        this.invoiceMatchResultRepository = invoiceMatchResultRepository;
        this.creditLedgerService = creditLedgerService;
        this.discrepancyCaseService = discrepancyCaseService;
        this.paymentTermsService = paymentTermsService;
    }

    /**
     * {@code REQUIRES_NEW} so the verdict always commits in its own transaction —
     * the match is driven from {@code @TransactionalEventListener(AFTER_COMMIT)},
     * and a plain {@code REQUIRED} write inside an after-commit callback silently
     * fails to persist (the triggering tx is already completing). One nested
     * level, well within the pool.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluate(UUID purchaseOrderId) {
        List<Payment> payments = paymentRepository.findAll().stream()
            .filter(payment -> payment.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();
        Payment deposit = payments.stream().filter(p -> p.getType() == PaymentType.DEPOSIT).findFirst().orElse(null);
        Payment balance = payments.stream().filter(p -> p.getType() == PaymentType.BALANCE).findFirst().orElse(null);

        PurchaseOrderReconciliationView po = purchaseOrderService.getReconciliationView(purchaseOrderId);
        Optional<PaymentTermsType> termsType = paymentTermsService.getEffectiveTermsType(po.supplierId());
        boolean gates = consequencePolicy.gatesPayments(termsType);
        String termsTypeName = termsType.map(Enum::name).orElse(null);
        MatchPolicy policy = gates ? MatchPolicy.PAYMENT_GATED : MatchPolicy.STATEMENT_RECORDED;

        List<Invoice> invoices = invoiceRepository.findAll().stream()
            .filter(inv -> inv.getPurchaseOrderId().equals(purchaseOrderId) && inv.isActive())
            .sorted(Comparator.comparing(Invoice::getCreatedAt).thenComparing(Invoice::getId))
            .toList();

        Optional<ConfirmedProformaInvoiceView> pi = proformaInvoiceMatchService.getConfirmedForMatch(purchaseOrderId);
        if (pi.isEmpty()) {
            // No agreed commercial position — nothing is matchable yet. But a deposit precedes the PI by design:
            // it's payable-without-match unless a deposit invoice (which needs the PI to match) has been logged.
            if (gates) {
                boolean hasDepositInvoice = invoices.stream()
                    .anyMatch(inv -> inv.getCoversType() == InvoiceCoversType.DEPOSIT);
                if (deposit != null && deposit.isMatchMutable() && !hasDepositInvoice) {
                    if (deposit.getStatus() != PaymentStatus.READY_TO_PAY) {
                        deposit.markPayableWithoutMatch();
                        paymentRepository.save(deposit);
                        paymentAuditService.record(deposit.getId(), purchaseOrderId,
                            PaymentAuditEventType.DEPOSIT_PAYABLE,
                            "Deposit payable without a match — no deposit invoice logged yet");
                    }
                } else {
                    markAwaiting(purchaseOrderId, deposit, "confirmed PI");
                }
                markAwaiting(purchaseOrderId, balance, "confirmed PI");
            }
            return;
        }

        List<GrnProjectionLine> grn = grnProjectionLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();

        Legs legs = buildLegs(pi.get(), po, grn, deposit, balance);
        Money poCoverage = orderedValue(po, legs.piCurrency());
        Money depositObligation = deposit != null ? deposit.getAmount() : Money.zero(legs.piCurrency());
        Map<UUID, Map<UUID, Integer>> grnByConsignment = grn.stream().collect(Collectors.groupingBy(
            GrnProjectionLine::getConsignmentId,
            Collectors.groupingBy(GrnProjectionLine::getSkuId, Collectors.summingInt(GrnProjectionLine::getReceivedQuantity))));

        // Evaluate each invoice in a stable order, accumulating matched value so the rollup sees collective over-claim.
        Map<UUID, Verdict> verdicts = new LinkedHashMap<>();
        Money matched = Money.zero(legs.piCurrency());
        for (Invoice inv : invoices) {
            CreditResolution credit = resolveCredit(purchaseOrderId, inv);
            Claim claim = buildClaim(inv, grnByConsignment, credit);
            Verdict verdict = InvoiceMatchEvaluator.evaluate(legs, claim, matched);
            if (verdict.passed() && inv.getAmount().currency().equals(legs.piCurrency())) {
                String rollup = MatchRollupEvaluator.check(
                    matched.plus(inv.getAmount()), legs.receivedValue(), depositObligation, poCoverage);
                if (rollup != null) {
                    verdict = new Verdict(false, verdict.positionMatched(), verdict.expected(), rollup);
                }
            }
            if (verdict.passed()) {
                if (credit.entryToApply() != null) {
                    creditLedgerService.apply(credit.entryToApply(), inv.getId());
                }
                matched = matched.plus(inv.getAmount());
            }
            verdicts.put(inv.getId(), verdict);
            recordResult(purchaseOrderId, inv, verdict, termsTypeName, policy);
        }

        if (gates) {
            applyDepositGate(purchaseOrderId, deposit, invoices, verdicts);
            applyBalanceGate(purchaseOrderId, balance, invoices, verdicts);
        } else {
            applyStatementRecording(balance, invoices, verdicts);
        }
    }

    /** The per-invoice match verdicts recorded for a PO — what Finance and (later) the statement view read. */
    @Transactional(readOnly = true)
    public List<com.shvoy.payments.dto.InvoiceMatchResultResponse> listResults(UUID purchaseOrderId) {
        purchaseOrderService.assertOwnPurchaseOrderExists(purchaseOrderId);
        return invoiceMatchResultRepository.findAll().stream()
            .filter(r -> r.getPurchaseOrderId().equals(purchaseOrderId))
            .sorted(Comparator.comparing(InvoiceMatchResult::getEvaluatedAt))
            .map(r -> new com.shvoy.payments.dto.InvoiceMatchResultResponse(
                r.getInvoiceId(), r.getCoversType(), r.getTermsType(), r.isPassed(), r.isPositionMatched(),
                r.getExpectedAmount(), r.getInvoiceAmount(), r.getCurrency(), r.getDetail(), r.getPolicyApplied(),
                r.getEvaluatedAt()))
            .toList();
    }

    // --- consequence: per-PO payment gating (deposit/balance & zero-deposit) ---

    private void applyDepositGate(UUID poId, Payment deposit, List<Invoice> invoices, Map<UUID, Verdict> verdicts) {
        if (deposit == null || !deposit.isMatchMutable()) {
            return;
        }
        List<Invoice> depositInvoices = invoices.stream()
            .filter(inv -> inv.getCoversType() == InvoiceCoversType.DEPOSIT).toList();
        if (depositInvoices.isEmpty()) {
            // A deposit precedes shipment/invoice by design — payable without a match until one is logged (flagged).
            if (deposit.getStatus() != PaymentStatus.READY_TO_PAY) {
                deposit.markPayableWithoutMatch();
                paymentRepository.save(deposit);
                paymentAuditService.record(deposit.getId(), poId, PaymentAuditEventType.DEPOSIT_PAYABLE,
                    "Deposit payable without a match — no deposit invoice logged yet");
            }
            return;
        }
        String failure = collectFailures(depositInvoices, verdicts);
        if (failure != null) {
            block(poId, deposit, failure);
        } else {
            pass(poId, deposit);
        }
    }

    private void applyBalanceGate(UUID poId, Payment balance, List<Invoice> invoices, Map<UUID, Verdict> verdicts) {
        if (balance == null) {
            return;
        }
        boolean held = balance.getStatus() == PaymentStatus.ON_HOLD;
        // A PAID or match-overridden payment is settled — the match never touches it. A HELD payment IS
        // touched, but only to re-assert a failure: the system's verdict wins over a hold when the match now
        // fails (Story 6.8), while a passing/incomplete re-match leaves the human hold standing.
        if (!balance.isMatchMutable() && !held) {
            return;
        }
        List<Invoice> nonDeposit = invoices.stream()
            .filter(inv -> inv.getCoversType() != InvoiceCoversType.DEPOSIT).toList();
        String failure = collectFailures(nonDeposit, verdicts);
        if (failure != null) {
            block(poId, balance, failure);
            return;
        }
        if (held) {
            return; // a clean/incomplete re-match doesn't lift a hold — Finance releases it explicitly
        }
        boolean completingBalancePassed = nonDeposit.stream()
            .anyMatch(inv -> inv.getCoversType() == InvoiceCoversType.BALANCE && verdicts.get(inv.getId()).passed());
        if (completingBalancePassed) {
            pass(poId, balance);
        } else {
            // Partial invoices can pass without releasing the balance — the balance is claimed by a BALANCE invoice.
            String reason = nonDeposit.isEmpty() ? "invoice" : "completing balance invoice";
            markAwaiting(poId, balance, reason);
        }
    }

    // --- consequence: rolling — record verdicts + cases, no payment transition ---

    private void applyStatementRecording(Payment balance, List<Invoice> invoices, Map<UUID, Verdict> verdicts) {
        if (balance == null) {
            return; // nothing to anchor a case to; the invoice_match_results rows already carry the verdicts
        }
        String failure = collectFailures(invoices, verdicts);
        if (failure != null) {
            discrepancyCaseService.onMatchBlocked(balance, failure);
        } else {
            discrepancyCaseService.onMatchPassed(balance);
        }
        // Deliberately no markMatch*/save — a rolling supplier's payments are settled against the statement, not per PO.
    }

    // --- payment transition helpers (block-by-default) ---

    private void pass(UUID poId, Payment payment) {
        if (payment.getStatus() != PaymentStatus.READY_TO_PAY) {
            payment.markMatchPassed();
            paymentRepository.save(payment);
            paymentAuditService.record(payment.getId(), poId, PaymentAuditEventType.MATCH_PASSED,
                "Three-way match passed — READY_TO_PAY");
        }
        discrepancyCaseService.onMatchPassed(payment);
    }

    private void block(UUID poId, Payment payment, String detail) {
        PaymentStatus prior = payment.getStatus();
        boolean changed = prior != PaymentStatus.BLOCKED || !Objects.equals(payment.getMatchDetail(), detail);
        payment.markMatchBlocked(detail);
        paymentRepository.save(payment);
        if (changed) {
            // Both facts (Story 6.8): a hold placed by Finance is overridden by the system's re-asserted failure.
            String auditDetail = prior == PaymentStatus.ON_HOLD
                ? "Re-match failed while ON_HOLD → BLOCKED (the hold is moot against a failed match). " + detail
                : detail;
            paymentAuditService.record(payment.getId(), poId, PaymentAuditEventType.MATCH_BLOCKED, auditDetail);
        }
        discrepancyCaseService.onMatchBlocked(payment, detail);
    }

    private void markAwaiting(UUID poId, Payment payment, String reason) {
        if (payment == null || !payment.isMatchMutable()) {
            return;
        }
        payment.markAwaiting("Awaiting " + reason);
        paymentRepository.save(payment);
    }

    private static String collectFailures(List<Invoice> invoices, Map<UUID, Verdict> verdicts) {
        List<String> failures = invoices.stream()
            .filter(inv -> !verdicts.get(inv.getId()).passed())
            .map(inv -> inv.getInvoiceReference() + ": " + verdicts.get(inv.getId()).detail())
            .toList();
        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    // --- leg gathering ---

    private Legs buildLegs(ConfirmedProformaInvoiceView pi, PurchaseOrderReconciliationView po,
            List<GrnProjectionLine> grn, Payment deposit, Payment balance) {
        String currency = pi.currency();
        Map<UUID, Integer> poQ = po.lines().stream().collect(Collectors.toMap(
            PurchaseOrderReconciliationLine::skuId, PurchaseOrderReconciliationLine::quantity, Integer::sum));
        Map<UUID, BigDecimal> piPrices = new HashMap<>();
        Map<UUID, Integer> piQ = new HashMap<>();
        for (ConfirmedProformaInvoiceLine line : pi.lines()) {
            piPrices.put(line.skuId(), line.confirmedUnitPriceAmount());
            piQ.merge(line.skuId(), line.confirmedQuantity(), Integer::sum);
        }
        Map<UUID, Integer> cumulativeGrn = grn.stream().collect(Collectors.groupingBy(
            GrnProjectionLine::getSkuId, Collectors.summingInt(GrnProjectionLine::getReceivedQuantity)));
        Money receivedValue = Money.zero(currency);
        for (Map.Entry<UUID, Integer> line : cumulativeGrn.entrySet()) {
            BigDecimal price = piPrices.get(line.getKey());
            if (price != null) {
                receivedValue = receivedValue.plus(new UnitPrice(price, currency).multiply(line.getValue()));
            }
        }
        return new Legs(currency, poQ, piPrices, piQ, cumulativeGrn, receivedValue,
            deposit == null ? null : deposit.getAmount(),
            balance == null ? null : balance.getAmount());
    }

    private Money orderedValue(PurchaseOrderReconciliationView po, String currency) {
        Money value = Money.zero(currency);
        for (PurchaseOrderReconciliationLine line : po.lines()) {
            if (line.unitPriceAmount() != null) {
                value = value.plus(new UnitPrice(line.unitPriceAmount(), currency).multiply(line.quantity()));
            }
        }
        return value;
    }

    private Claim buildClaim(Invoice inv, Map<UUID, Map<UUID, Integer>> grnByConsignment, CreditResolution credit) {
        InvoiceCoversType covers = inv.getCoversType();
        Map<UUID, Integer> shipmentGrn = Map.of();
        boolean shipmentReceipted = false;
        if (covers == InvoiceCoversType.SHIPMENT && inv.getCoversConsignmentId() != null) {
            shipmentReceipted = grnByConsignment.containsKey(inv.getCoversConsignmentId());
            shipmentGrn = grnByConsignment.getOrDefault(inv.getCoversConsignmentId(), Map.of());
        }
        Map<UUID, Integer> claimedLines = Map.of();
        if (covers == InvoiceCoversType.LINES) {
            claimedLines = invoiceCoveredLineRepository.findAll().stream()
                .filter(line -> line.getInvoiceId().equals(inv.getId()))
                .collect(Collectors.toMap(l -> l.getSkuId(), l -> l.getQuantity(), Integer::sum));
        }
        return new Claim(covers, inv.getAmount(), inv.getClaimedCredit(), credit.valid(), credit.outcome(),
            shipmentGrn, shipmentReceipted, claimedLines);
    }

    // --- credit resolution (6.7, unchanged) ---

    private CreditResolution resolveCredit(UUID poId, Invoice inv) {
        Money claimedCredit = inv.getClaimedCredit();
        if (claimedCredit == null) {
            return new CreditResolution(true, null, null);
        }
        CreditMatchResult result = creditLedgerService.checkClaim(poId, claimedCredit);
        if (result.matched()) {
            return new CreditResolution(true, result.matchedEntryId(), null);
        }
        if (creditLedgerService.claimAlreadyAppliedToInvoice(poId, claimedCredit, inv.getId())) {
            return new CreditResolution(true, null, null);
        }
        return new CreditResolution(false, null, result.outcome().name());
    }

    private void recordResult(UUID poId, Invoice inv, Verdict verdict, String termsTypeName, MatchPolicy policy) {
        BigDecimal expected = verdict.expected() == null ? null : verdict.expected().amount();
        Optional<InvoiceMatchResult> existing = invoiceMatchResultRepository.findAll().stream()
            .filter(r -> r.getInvoiceId().equals(inv.getId())).findFirst();
        if (existing.isPresent()) {
            existing.get().update(inv.getCoversType(), termsTypeName, verdict.passed(), verdict.positionMatched(),
                expected, inv.getAmount().amount(), inv.getAmount().currency(), verdict.detail(), policy);
            invoiceMatchResultRepository.save(existing.get());
        } else {
            invoiceMatchResultRepository.save(new InvoiceMatchResult(poId, inv.getId(), inv.getCoversType(),
                termsTypeName, verdict.passed(), verdict.positionMatched(), expected, inv.getAmount().amount(),
                inv.getAmount().currency(), verdict.detail(), policy));
        }
    }

    private record CreditResolution(boolean valid, UUID entryToApply, String outcome) {
    }
}
