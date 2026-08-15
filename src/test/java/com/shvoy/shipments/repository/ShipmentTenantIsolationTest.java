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
import com.shvoy.shipments.domain.Shipment;

/**
 * Mirrors ProformaInvoiceTenantIsolationTest — see SupplierTenantIsolationTest's
 * Javadoc for why this avoids class-level @Transactional and seeds via raw JDBC.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentTenantIsolationTest {

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID shipmentAId = UUID.randomUUID();
    final UUID shipmentBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOneShipmentEach() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        jdbcTemplate.update(
            "INSERT INTO shipments (id, bl_reference, bl_date, created_at, company_id) VALUES (?, 'BL-A', ?, ?, ?)",
            shipmentAId, Timestamp.valueOf(LocalDate.now().atStartOfDay()), now, companyA);
        jdbcTemplate.update(
            "INSERT INTO shipments (id, bl_reference, bl_date, created_at, company_id) VALUES (?, 'BL-B', ?, ?, ?)",
            shipmentBId, Timestamp.valueOf(LocalDate.now().atStartOfDay()), now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM shipments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsShipmentsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<Shipment> visible = shipmentRepository.findAll();
            assertThat(visible).extracting(Shipment::getId).containsExactly(shipmentAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysShipmentById() {
        TenantContext.set(companyA);
        try {
            assertThat(shipmentRepository.findById(shipmentBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAutomatically() {
        UUID newShipmentId;
        TenantContext.set(companyA);
        try {
            Shipment saved = shipmentRepository.save(
                new Shipment("BL-A-2", LocalDate.now(), LocalDate.now().minusDays(3), null));
            newShipmentId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM shipments WHERE id = ?", UUID.class, newShipmentId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM shipments WHERE id = ?", newShipmentId);
    }
}
