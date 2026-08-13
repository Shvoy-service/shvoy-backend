package com.shvoy.purchaseorders.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.TenantContext;

/**
 * No class-level @Transactional — PoNumberGenerator manages its own
 * transaction per call (see its Javadoc), same reasoning as every other
 * tenant-scoped test in this codebase that seeds via raw JDBC.
 *
 * Every call to claimNext needs TenantContext set first, same as any
 * TenantScoped repository call (see SkuPriceTenantIsolationTest) — even
 * though claimNext itself only touches JdbcTemplate, this app's default
 * PlatformTransactionManager is JPA-backed, so opening @Transactional's
 * EntityManager still needs a resolvable tenant (see TenancyConfig). In
 * production this is already satisfied by TenantContextFilter before any
 * service method runs; a plain unit-style test like this one has to set
 * it explicitly instead.
 */
@SpringBootTest
@ActiveProfiles("test")
class PoNumberGeneratorTest {

    @Autowired
    PoNumberGenerator poNumberGenerator;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();

    @BeforeEach
    void seedCompanies() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM po_number_counters WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void firstClaimForACompanyIsNumberOne() {
        TenantContext.set(companyA);
        try {
            assertThat(poNumberGenerator.claimNext(companyA)).isEqualTo("PO-0001");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void successiveClaimsForTheSameCompanyIncrement() {
        TenantContext.set(companyA);
        try {
            assertThat(poNumberGenerator.claimNext(companyA)).isEqualTo("PO-0001");
            assertThat(poNumberGenerator.claimNext(companyA)).isEqualTo("PO-0002");
            assertThat(poNumberGenerator.claimNext(companyA)).isEqualTo("PO-0003");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void differentCompaniesEachGetTheirOwnSequenceStartingAtOne() {
        TenantContext.set(companyA);
        try {
            assertThat(poNumberGenerator.claimNext(companyA)).isEqualTo("PO-0001");
        } finally {
            TenantContext.clear();
        }
        TenantContext.set(companyB);
        try {
            assertThat(poNumberGenerator.claimNext(companyB)).isEqualTo("PO-0001");
        } finally {
            TenantContext.clear();
        }
        TenantContext.set(companyA);
        try {
            assertThat(poNumberGenerator.claimNext(companyA)).isEqualTo("PO-0002");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Regression-style test for the same class of race SkuService#insertPrice
     * used to have (see that fix): fires many concurrent claims for the same
     * company and asserts the result is exactly {1..N} with no duplicates
     * and no gaps — proving the SELECT ... FOR UPDATE lock actually
     * serializes claims rather than letting two racing calls read the same
     * last_number and both increment from it.
     */
    @Test
    void concurrentClaimsForTheSameCompanyNeverDuplicateOrSkipANumber() throws Exception {
        int attempts = 10;
        Callable<String> claim = () -> {
            // TenantContext is thread-local — each racing thread needs its
            // own, not just the test method's main thread.
            TenantContext.set(companyA);
            try {
                return poNumberGenerator.claimNext(companyA);
            } finally {
                TenantContext.clear();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<String> numbers;
        try {
            List<Future<String>> futures = executor.invokeAll(IntStream.range(0, attempts)
                .mapToObj(i -> claim)
                .toList());
            numbers = futures.stream().map(this::getUnchecked).toList();
        } finally {
            executor.shutdown();
        }

        List<String> expected = IntStream.rangeClosed(1, attempts)
            .mapToObj("PO-%04d"::formatted)
            .toList();
        assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
    }

    private String getUnchecked(Future<String> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
