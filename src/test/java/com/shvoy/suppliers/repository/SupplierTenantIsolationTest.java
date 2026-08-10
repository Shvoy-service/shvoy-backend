package com.shvoy.suppliers.repository;

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
import com.shvoy.suppliers.domain.Supplier;

/**
 * Mirrors onboarding.repository.TenantIsolationTest — see its Javadoc for
 * why this deliberately avoids class-level @Transactional (Hibernate
 * resolves the tenant when a Session opens, which happens before
 * @BeforeEach under Spring's transactional test rollback) and seeds via
 * raw JDBC instead of the tenant-filtered ORM path.
 */
@SpringBootTest
@ActiveProfiles("test")
class SupplierTenantIsolationTest {

    @Autowired
    SupplierRepository supplierRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID supplierAId = UUID.randomUUID();
    final UUID supplierBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOneSupplierEach() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, ?, ?, ?)",
            supplierAId, "Supplier A", "ACTIVE", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, ?, ?, ?)",
            supplierBId, "Supplier B", "ACTIVE", now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsRecordsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<Supplier> visible = supplierRepository.findAll();
            assertThat(visible).extracting(Supplier::getId).containsExactly(supplierAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysSupplierById() {
        TenantContext.set(companyA);
        try {
            assertThat(supplierRepository.findById(supplierBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAutomatically() {
        UUID newSupplierId;
        TenantContext.set(companyA);
        try {
            Supplier saved = supplierRepository.save(new Supplier("New Supplier"));
            newSupplierId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM suppliers WHERE id = ?", UUID.class, newSupplierId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM suppliers WHERE id = ?", newSupplierId);
    }
}
