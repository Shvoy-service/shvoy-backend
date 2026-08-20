package com.shvoy.purchaseorders.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.shvoy.EmailContent;

class PurchaseOrderEmailComposerTest {

    private final PurchaseOrderEmailComposer composer = new PurchaseOrderEmailComposer();

    @Test
    void addressesTheSupplierAndNamesTheBuyingCompanyAndPo() {
        EmailContent content = composer.compose("PO-0042", "Acme Ltd", "Shenzhen Widgets Co");

        assertThat(content.subject()).isEqualTo("Purchase Order PO-0042");
        assertThat(content.body())
            .startsWith("Dear Shenzhen Widgets Co,")
            .contains("purchase order PO-0042 from Acme Ltd")
            .contains("attached PDF");
    }
}
