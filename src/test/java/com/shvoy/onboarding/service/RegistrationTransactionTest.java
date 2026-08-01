package com.shvoy.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.shvoy.TenantContext;
import com.shvoy.onboarding.domain.Company;
import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.repository.CompanyRepository;
import com.shvoy.onboarding.repository.UserRepository;

/**
 * Proves the transactional guarantee RegistrationService.register() relies
 * on, at the level actually being guaranteed (the database) rather than
 * through the service's own pre-check — which would short-circuit before
 * ever reaching the failure being tested here.
 */
@SpringBootTest
@ActiveProfiles("test")
class RegistrationTransactionTest {

    @Autowired
    CompanyRepository companyRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    UUID existingCompanyId;
    UUID newCompanyId;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", existingCompanyId, newCompanyId);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", existingCompanyId, newCompanyId);
    }

    @Test
    void failedUserInsertRollsBackCompanyInsert() {
        existingCompanyId = UUID.randomUUID();
        newCompanyId = UUID.randomUUID();
        String takenEmail = "taken-" + UUID.randomUUID() + "@example.com";

        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)",
            existingCompanyId, "Existing Co", Timestamp.from(Instant.now()));
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), takenEmail, "ADMIN", "ACTIVE", Timestamp.from(Instant.now()), existingCompanyId);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // TenantContext must be set BEFORE the transaction opens, not
        // inside the callback — the transaction template opens the
        // EntityManager as part of starting the transaction, before the
        // callback body runs, same as a @Transactional proxy would.
        TenantContext.set(newCompanyId);
        try {
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                companyRepository.save(new Company(newCompanyId, "New Co"));
                userRepository.saveAndFlush(new User(takenEmail, Role.ADMIN));
            })).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            TenantContext.clear();
        }

        TenantContext.set(newCompanyId);
        try {
            assertThat(companyRepository.findById(newCompanyId)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }
}
