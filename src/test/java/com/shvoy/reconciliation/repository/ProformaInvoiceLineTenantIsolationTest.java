package com.shvoy.reconciliation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.TenantContext;
import com.shvoy.reconciliation.domain.ProformaInvoiceLine;

/**
 * Mirrors PurchaseOrderLineTenantIsolationTest — see
 * SupplierTenantIsolationTest's Javadoc for why this avoids class-level
 * @Transactional and seeds via raw JDBC.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProformaInvoiceLineTenantIsolationTest {

    @Autowired
    ProformaInvoiceLineRepository proformaInvoiceLineRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID supplierAId = UUID.randomUUID();
    final UUID supplierBId = UUID.randomUUID();
    final UUID userAId = UUID.randomUUID();
    final UUID userBId = UUID.randomUUID();
    final UUID skuAId = UUID.randomUUID();
    final UUID skuBId = UUID.randomUUID();
    final UUID poAId = UUID.randomUUID();
    final UUID poBId = UUID.randomUUID();
    final UUID piAId = UUID.randomUUID();
    final UUID piBId = UUID.randomUUID();
    final UUID lineAId = UUID.randomUUID();
    final UUID lineBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOneProformaInvoiceLineEach() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierBId, "Supplier B", now, companyB);
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userBId, "admin-b@example.com", now, companyB);
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, 'SKU-A', 'ACTIVE', ?, ?)",
            skuAId, supplierAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, 'SKU-B', 'ACTIVE', ?, ?)",
            skuBId, supplierBId, now, companyB);
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            poAId, supplierAId, userAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            poBId, supplierBId, userBId, now, companyB);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices "
                + "(id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, 'SUP-PI-A', 'USD', 'LOGGED', true, ?, ?, ?)",
            piAId, poAId, userAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices "
                + "(id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, 'SUP-PI-B', 'USD', 'LOGGED', true, ?, ?, ?)",
            piBId, poBId, userBId, now, companyB);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoice_lines "
                + "(id, proforma_invoice_id, sku_id, line_number, confirmed_unit_price_amount, confirmed_quantity, created_at, company_id) "
                + "VALUES (?, ?, ?, 1, 1.5000, 10, ?, ?)",
            lineAId, piAId, skuAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoice_lines "
                + "(id, proforma_invoice_id, sku_id, line_number, confirmed_unit_price_amount, confirmed_quantity, created_at, company_id) "
                + "VALUES (?, ?, ?, 1, 2.5000, 20, ?, ?)",
            lineBId, piBId, skuBId, now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsRecordsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<ProformaInvoiceLine> visible = proformaInvoiceLineRepository.findAll();
            assertThat(visible).extracting(ProformaInvoiceLine::getId).containsExactly(lineAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysLineById() {
        TenantContext.set(companyA);
        try {
            assertThat(proformaInvoiceLineRepository.findById(lineBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAutomatically() {
        UUID newLineId;
        TenantContext.set(companyA);
        try {
            ProformaInvoiceLine saved = proformaInvoiceLineRepository.save(
                new ProformaInvoiceLine(piAId, skuAId, 2, new BigDecimal("3.2500"), 5));
            newLineId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
            assertThat(saved.getConfirmedUnitPriceAmount()).isEqualByComparingTo("3.2500");
            assertThat(saved.getConfirmedQuantity()).isEqualTo(5);
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM proforma_invoice_lines WHERE id = ?", UUID.class, newLineId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE id = ?", newLineId);
    }
}
