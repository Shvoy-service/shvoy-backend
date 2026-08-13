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
import com.shvoy.suppliers.domain.Sku;

/**
 * Mirrors SupplierTenantIsolationTest — see its Javadoc for why this
 * deliberately avoids class-level @Transactional and seeds via raw JDBC.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkuTenantIsolationTest {

    @Autowired
    SkuRepository skuRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID supplierAId = UUID.randomUUID();
    final UUID supplierBId = UUID.randomUUID();
    final UUID skuAId = UUID.randomUUID();
    final UUID skuBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOneSkuEach() {
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
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            skuAId, supplierAId, "SKU-A", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            skuBId, supplierBId, "SKU-B", now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsRecordsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<Sku> visible = skuRepository.findAll();
            assertThat(visible).extracting(Sku::getId).containsExactly(skuAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysSkuById() {
        TenantContext.set(companyA);
        try {
            assertThat(skuRepository.findById(skuBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAutomatically() {
        UUID newSkuId;
        TenantContext.set(companyA);
        try {
            Sku saved = skuRepository.save(new Sku(supplierAId, "SKU-NEW", null));
            newSkuId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM skus WHERE id = ?", UUID.class, newSkuId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM skus WHERE id = ?", newSkuId);
    }
}
