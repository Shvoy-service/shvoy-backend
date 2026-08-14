package com.shvoy.suppliers.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import com.shvoy.UnitPrice;
import com.shvoy.suppliers.domain.SkuPrice;

/**
 * Mirrors SkuTenantIsolationTest/SupplierTenantIsolationTest — see the
 * latter's Javadoc for why this avoids class-level @Transactional and seeds
 * via raw JDBC.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkuPriceTenantIsolationTest {

    @Autowired
    SkuPriceRepository skuPriceRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID supplierAId = UUID.randomUUID();
    final UUID supplierBId = UUID.randomUUID();
    final UUID skuAId = UUID.randomUUID();
    final UUID skuBId = UUID.randomUUID();
    final UUID priceAId = UUID.randomUUID();
    final UUID priceBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOneSkuPriceEach() {
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
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            priceAId, skuAId, new BigDecimal("1.4275"), "USD", LocalDate.now(), now, companyA);
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            priceBId, skuBId, new BigDecimal("2.5000"), "USD", LocalDate.now(), now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsRecordsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<SkuPrice> visible = skuPriceRepository.findAll();
            assertThat(visible).extracting(SkuPrice::getId).containsExactly(priceAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchAnotherCompanysSkuPriceById() {
        TenantContext.set(companyA);
        try {
            assertThat(skuPriceRepository.findById(priceBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void savingUnderATenantPopulatesCompanyIdAutomatically() {
        UUID newPriceId;
        TenantContext.set(companyA);
        try {
            SkuPrice saved = skuPriceRepository.save(
                new SkuPrice(skuAId, new UnitPrice(new BigDecimal("9.9999"), "USD"), LocalDate.now(), null));
            newPriceId = saved.getId();
            assertThat(saved.getCompanyId()).isEqualTo(companyA);
        } finally {
            TenantContext.clear();
        }

        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM sku_prices WHERE id = ?", UUID.class, newPriceId);
        assertThat(storedCompanyId).isEqualTo(companyA);

        jdbcTemplate.update("DELETE FROM sku_prices WHERE id = ?", newPriceId);
    }
}
