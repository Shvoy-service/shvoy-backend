package com.shvoy.shipments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import com.shvoy.shipments.domain.ShipmentConsignment;

/**
 * Mirrors ProformaInvoiceTenantIsolationTest — see SupplierTenantIsolationTest's
 * Javadoc for why this avoids class-level @Transactional and seeds via raw JDBC.
 *
 * <p>Company A's shipment is <strong>co-loaded</strong>: one BL carrying two
 * consignments for two <em>different suppliers</em>. That is the case that
 * proves the load-bearing rule — co-loading spans suppliers but never crosses
 * the company boundary (see {@link #coLoadingSpansSuppliersButNeverCompanies}).
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentConsignmentTenantIsolationTest {

    @Autowired
    ShipmentConsignmentRepository consignmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID supplierA1Id = UUID.randomUUID();
    final UUID supplierA2Id = UUID.randomUUID();
    final UUID supplierBId = UUID.randomUUID();
    final UUID userAId = UUID.randomUUID();
    final UUID userBId = UUID.randomUUID();
    final UUID poA1Id = UUID.randomUUID();
    final UUID poA2Id = UUID.randomUUID();
    final UUID poBId = UUID.randomUUID();
    final UUID shipmentAId = UUID.randomUUID();
    final UUID shipmentBId = UUID.randomUUID();
    final UUID consignmentA1Id = UUID.randomUUID();
    final UUID consignmentA2Id = UUID.randomUUID();
    final UUID consignmentBId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp blDate = Timestamp.valueOf(LocalDate.now().atStartOfDay());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);

        // Company A: two suppliers co-loaded onto one BL.
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierA1Id, "Supplier A1", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierA2Id, "Supplier A2", now, companyA);
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
                + "VALUES (?, ?, 'PO-A1', 'SENT', ?, ?, ?)",
            poA1Id, supplierA1Id, userAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-A2', 'SENT', ?, ?, ?)",
            poA2Id, supplierA2Id, userAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-B1', 'SENT', ?, ?, ?)",
            poBId, supplierBId, userBId, now, companyB);

        jdbcTemplate.update(
            "INSERT INTO shipments (id, bl_reference, bl_date, created_at, company_id) VALUES (?, 'BL-A', ?, ?, ?)",
            shipmentAId, blDate, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO shipments (id, bl_reference, bl_date, created_at, company_id) VALUES (?, 'BL-B', ?, ?, ?)",
            shipmentBId, blDate, now, companyB);

        // Two consignments on A's single BL — one per supplier — and one on B's.
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments "
                + "(id, shipment_id, purchase_order_id, receipt_status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'DOCUMENTS_PENDING', ?, ?)",
            consignmentA1Id, shipmentAId, poA1Id, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments "
                + "(id, shipment_id, purchase_order_id, receipt_status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'DOCUMENTS_PENDING', ?, ?)",
            consignmentA2Id, shipmentAId, poA2Id, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments "
                + "(id, shipment_id, purchase_order_id, receipt_status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'DOCUMENTS_PENDING', ?, ?)",
            consignmentBId, shipmentBId, poBId, now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_order_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsConsignmentsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<ShipmentConsignment> visible = consignmentRepository.findAll();
            assertThat(visible)
                .extracting(ShipmentConsignment::getId)
                .containsExactlyInAnyOrder(consignmentA1Id, consignmentA2Id);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysConsignmentById() {
        TenantContext.set(companyA);
        try {
            assertThat(consignmentRepository.findById(consignmentBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The load-bearing acceptance test: A's two consignments are for two
     * <em>different suppliers</em> yet share the one BL (co-loaded), and B's
     * consignment on B's own BL is never visible under tenant A. Co-loading
     * spans suppliers; the tenancy boundary is untouched by it.
     */
    @Test
    void coLoadingSpansSuppliersButNeverCompanies() {
        TenantContext.set(companyA);
        try {
            List<ShipmentConsignment> visible = consignmentRepository.findAll();

            // Both of A's consignments are co-loaded onto the single BL...
            assertThat(visible)
                .extracting(ShipmentConsignment::getShipmentId)
                .containsExactly(shipmentAId, shipmentAId);
            // ...across two distinct POs (two suppliers)...
            assertThat(visible)
                .extracting(ShipmentConsignment::getPurchaseOrderId)
                .containsExactlyInAnyOrder(poA1Id, poA2Id);
            // ...and B's consignment never leaks across the company boundary.
            assertThat(visible)
                .extracting(ShipmentConsignment::getId)
                .doesNotContain(consignmentBId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAndDefaultsToDocumentsPending() {
        UUID newConsignmentId;
        TenantContext.set(companyA);
        try {
            ShipmentConsignment saved = consignmentRepository.save(
                new ShipmentConsignment(shipmentAId, poA1Id));
            newConsignmentId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
            assertThat(saved.getReceiptStatus().name()).isEqualTo("DOCUMENTS_PENDING");
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM shipment_consignments WHERE id = ?", UUID.class, newConsignmentId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM shipment_consignments WHERE id = ?", newConsignmentId);
    }
}
