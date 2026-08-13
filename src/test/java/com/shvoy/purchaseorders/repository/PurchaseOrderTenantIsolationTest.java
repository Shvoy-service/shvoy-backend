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
import com.shvoy.purchaseorders.domain.PurchaseOrder;

/**
 * Mirrors SupplierTenantIsolationTest/SkuTenantIsolationTest — see the
 * former's Javadoc for why this avoids class-level @Transactional and seeds
 * via raw JDBC.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseOrderTenantIsolationTest {

    @Autowired
    PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID supplierAId = UUID.randomUUID();
    final UUID supplierBId = UUID.randomUUID();
    final UUID userAId = UUID.randomUUID();
    final UUID userBId = UUID.randomUUID();
    final UUID poAId = UUID.randomUUID();
    final UUID poBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOnePurchaseOrderEach() {
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
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            poAId, supplierAId, userAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            poBId, supplierBId, userBId, now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsRecordsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<PurchaseOrder> visible = purchaseOrderRepository.findAll();
            assertThat(visible).extracting(PurchaseOrder::getId).containsExactly(poAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysPurchaseOrderById() {
        TenantContext.set(companyA);
        try {
            assertThat(purchaseOrderRepository.findById(poBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAutomatically() {
        UUID newPoId;
        TenantContext.set(companyA);
        try {
            PurchaseOrder saved = purchaseOrderRepository.save(
                new PurchaseOrder(supplierAId, "PO-0002", userAId));
            newPoId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM purchase_orders WHERE id = ?", UUID.class, newPoId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM purchase_orders WHERE id = ?", newPoId);
    }
}
