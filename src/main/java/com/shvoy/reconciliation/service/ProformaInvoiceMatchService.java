package com.shvoy.reconciliation.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.domain.ProformaInvoiceLine;
import com.shvoy.reconciliation.domain.ProformaInvoiceStatus;
import com.shvoy.reconciliation.dto.ConfirmedProformaInvoiceLine;
import com.shvoy.reconciliation.dto.ConfirmedProformaInvoiceView;
import com.shvoy.reconciliation.repository.ProformaInvoiceLineRepository;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;

/**
 * The Feature 5 → Feature 6 read surface (Story 6.5): the confirmed PI leg of
 * the three-way match. {@code @NamedInterface} so {@code payments} can pull it
 * without reaching into the reconciliation module's internals — the same
 * pattern as {@code PurchaseOrderService}'s cross-module surface.
 *
 * <p>"Confirmed" is strict: the <em>active</em> PI for the PO whose status is
 * {@code AUTO_CONFIRMED} or {@code APPROVED}. A PI still routed for approval, or
 * rejected, is <strong>not</strong> a confirmed leg — {@link #getConfirmedForMatch}
 * returns empty, and the match cannot pass without it.
 */
@NamedInterface("reconciliation")
@Service
public class ProformaInvoiceMatchService {

    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ProformaInvoiceLineRepository proformaInvoiceLineRepository;

    ProformaInvoiceMatchService(ProformaInvoiceRepository proformaInvoiceRepository,
            ProformaInvoiceLineRepository proformaInvoiceLineRepository) {
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.proformaInvoiceLineRepository = proformaInvoiceLineRepository;
    }

    @Transactional(readOnly = true)
    public Optional<ConfirmedProformaInvoiceView> getConfirmedForMatch(UUID purchaseOrderId) {
        Optional<ProformaInvoice> confirmed = proformaInvoiceRepository.findAll().stream()
            .filter(ProformaInvoice::isActive)
            .filter(pi -> pi.getPurchaseOrderId().equals(purchaseOrderId))
            .filter(pi -> pi.getStatus() == ProformaInvoiceStatus.AUTO_CONFIRMED
                || pi.getStatus() == ProformaInvoiceStatus.APPROVED)
            .findFirst();
        if (confirmed.isEmpty()) {
            return Optional.empty();
        }
        ProformaInvoice pi = confirmed.get();
        List<ConfirmedProformaInvoiceLine> lines = proformaInvoiceLineRepository.findAll().stream()
            .filter(line -> line.getProformaInvoiceId().equals(pi.getId()))
            .sorted(Comparator.comparingInt(ProformaInvoiceLine::getLineNumber))
            .map(line -> new ConfirmedProformaInvoiceLine(
                line.getSkuId(), line.getConfirmedUnitPriceAmount(), line.getConfirmedQuantity()))
            .toList();
        return Optional.of(new ConfirmedProformaInvoiceView(pi.getId(), pi.getPurchaseOrderId(), pi.getCurrency(), lines));
    }
}
