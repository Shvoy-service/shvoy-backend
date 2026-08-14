package com.shvoy.purchaseorders.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.Money;
import com.shvoy.UnitPrice;

@SpringBootTest
@ActiveProfiles("test")
class PurchaseOrderDocumentRendererTest {

    @Autowired
    PurchaseOrderDocumentRenderer renderer;

    @Test
    void rendersAWellFormedPdfWithAllFieldsPopulated() {
        PurchaseOrderDocumentData data = new PurchaseOrderDocumentData(
            "PO-0001",
            "Acme Corp",
            "United Kingdom",
            "sales@acme.example",
            LocalDate.of(2026, 3, 15),
            List.of(
                new PurchaseOrderDocumentData.LineItem(
                    "SKU-1", "Widget", 150,
                    new UnitPrice(new BigDecimal("1.5000"), "USD"), 100,
                    new Money(new BigDecimal("225.00"), "USD")),
                new PurchaseOrderDocumentData.LineItem(
                    "SKU-2", "Gadget", 5,
                    new UnitPrice(new BigDecimal("2.0000"), "USD"), null,
                    new Money(new BigDecimal("10.00"), "USD"))),
            new Money(new BigDecimal("235.00"), "USD"),
            new Money(new BigDecimal("70.50"), "USD"),
            new Money(new BigDecimal("164.50"), "USD"),
            Instant.parse("2026-03-16T09:30:00Z"));

        byte[] pdf = renderer.render(data);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void rendersWithoutADepositBalanceSplitWhenNoPaymentTermsAreConfigured() {
        PurchaseOrderDocumentData data = new PurchaseOrderDocumentData(
            "PO-0002",
            "Acme Corp",
            null,
            null,
            LocalDate.of(2026, 3, 15),
            List.of(new PurchaseOrderDocumentData.LineItem(
                "SKU-1", "Widget", 10,
                new UnitPrice(new BigDecimal("2.0000"), "USD"), null,
                new Money(new BigDecimal("20.00"), "USD"))),
            new Money(new BigDecimal("20.00"), "USD"),
            null,
            null,
            Instant.parse("2026-03-16T09:30:00Z"));

        byte[] pdf = renderer.render(data);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
