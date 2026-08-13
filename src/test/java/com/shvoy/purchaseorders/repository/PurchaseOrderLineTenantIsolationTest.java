package com.shvoy.purchaseorders.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;

/**
 * Mirrors PurchaseOrderTenantIsolationTest/SkuPriceTenantIsolationTest —
 * see SupplierTenantIsolationTest's Javadoc for why this avoids
 * class-level @Transactional and seeds via raw JDBC.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseOrderLineTenantIsolationTest {

    @Autowired
    PurchaseOrderLineRepository purchaseOrderLineRepository;

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
    final UUID lineAId = UUID.randomUUID();
    final UUID lineBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOnePurchaseOrderLineEach() {
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
            "INSERT INTO purchase_order_lines (id, purchase_order_id, sku_id, line_number, quantity, created_at, company_id) "
                + "VALUES (?, ?, ?, 1, 10, ?, ?)",
            lineAId, poAId, skuAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, purchase_order_id, sku_id, line_number, quantity, created_at, company_id) "
                + "VALUES (?, ?, ?, 1, 20, ?, ?)",
            lineBId, poBId, skuBId, now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id IN (?, ?)", companyA, companyB);
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
            List<PurchaseOrderLine> visible = purchaseOrderLineRepository.findAll();
            assertThat(visible).extracting(PurchaseOrderLine::getId).containsExactly(lineAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysLineById() {
        TenantContext.set(companyA);
        try {
            assertThat(purchaseOrderLineRepository.findById(lineBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAutomatically() {
        UUID newLineId;
        TenantContext.set(companyA);
        try {
            PurchaseOrderLine saved = purchaseOrderLineRepository.save(
                new PurchaseOrderLine(poAId, skuAId, 2, 5));
            newLineId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
            assertThat(saved.getUnitPrice()).isNull();
            assertThat(saved.getLineTotal()).isNull();
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM purchase_order_lines WHERE id = ?", UUID.class, newLineId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE id = ?", newLineId);
    }
}
