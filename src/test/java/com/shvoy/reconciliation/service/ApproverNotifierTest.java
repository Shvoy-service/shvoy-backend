package com.shvoy.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;
import com.shvoy.FrontendLinks;
import com.shvoy.TenantContext;
import com.shvoy.onboarding.service.ApproverPoolService;
import com.shvoy.onboarding.service.UserDirectoryService;
import com.shvoy.purchaseorders.dto.PurchaseOrderSummary;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.service.SupplierService;

/**
 * The one behaviour change in Story 9.5: {@link ApproverNotifier} branches its
 * recipients on the 2-of-N gate — the eligible pool for a price increase, all
 * active APPROVER-role holders otherwise. A focused unit test around the seams;
 * the gate rule itself is defined and tested in {@code PiApprovalService}.
 */
class ApproverNotifierTest {

    private final UUID companyA = UUID.randomUUID();
    private final UUID piId = UUID.randomUUID();
    private final UUID poId = UUID.randomUUID();
    private final UUID supplierId = UUID.randomUUID();

    private final ProformaInvoiceRepository piRepository = mock(ProformaInvoiceRepository.class);
    private final ApproverPoolService approverPoolService = mock(ApproverPoolService.class);
    private final UserDirectoryService userDirectoryService = mock(UserDirectoryService.class);
    private final PiApprovalService piApprovalService = mock(PiApprovalService.class);
    private final PurchaseOrderService purchaseOrderService = mock(PurchaseOrderService.class);
    private final SupplierService supplierService = mock(SupplierService.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final ApprovalRequestEmailComposer composer =
        new ApprovalRequestEmailComposer(new FrontendLinks("http://localhost:5173"));

    private final ApproverNotifier notifier = new ApproverNotifier(
        piRepository, approverPoolService, userDirectoryService, piApprovalService,
        purchaseOrderService, supplierService, composer, emailSender);

    @BeforeEach
    void setUp() {
        TenantContext.set(companyA);
        ProformaInvoice pi = mock(ProformaInvoice.class);
        when(pi.getCompanyId()).thenReturn(companyA);
        when(pi.getPurchaseOrderId()).thenReturn(poId);
        when(pi.getPiReference()).thenReturn("PI-1");
        when(piRepository.findById(piId)).thenReturn(Optional.of(pi));
        when(purchaseOrderService.getSummary(poId)).thenReturn(new PurchaseOrderSummary(poId, "PO-0042", supplierId));
        when(supplierService.getSummary(supplierId))
            .thenReturn(new SupplierSummary(supplierId, "Shenzhen Widgets Co", "CN", "sales@s.example"));
        when(approverPoolService.requiredSignOffCount()).thenReturn(2);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void priceIncreasePathNotifiesTheEligiblePool() {
        when(piApprovalService.requiresPriceIncreaseSignOff(piId)).thenReturn(true);
        when(approverPoolService.resolveEligibleApproverEmails())
            .thenReturn(List.of("pool1@x.example", "pool2@x.example"));

        notifier.notifyRouted(piId);

        verify(userDirectoryService, never()).resolveApproverRoleEmails();
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(2)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(EmailMessage::to)
            .containsExactlyInAnyOrder("pool1@x.example", "pool2@x.example");
        assertThat(captor.getAllValues().get(0).body())
            .contains("2 distinct approver sign-offs")
            .contains("http://localhost:5173/reconciliation/" + piId);
    }

    @Test
    void singleApproverPathNotifiesAllApproverRoleHolders() {
        when(piApprovalService.requiresPriceIncreaseSignOff(piId)).thenReturn(false);
        when(userDirectoryService.resolveApproverRoleEmails()).thenReturn(List.of("approver@x.example"));

        notifier.notifyRouted(piId);

        verify(approverPoolService, never()).resolveEligibleApproverEmails();
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(1)).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("approver@x.example");
        assertThat(captor.getValue().body()).contains("single approver");
    }

    @Test
    void noEligibleRecipientsSendsNothingAndSkipsContentAssembly() {
        when(piApprovalService.requiresPriceIncreaseSignOff(piId)).thenReturn(false);
        when(userDirectoryService.resolveApproverRoleEmails()).thenReturn(List.of());

        notifier.notifyRouted(piId);

        verifyNoInteractions(emailSender);
        verifyNoInteractions(purchaseOrderService);
    }
}
