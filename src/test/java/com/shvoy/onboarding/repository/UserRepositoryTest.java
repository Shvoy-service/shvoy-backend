package com.shvoy.onboarding.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndReadsBackRoleAsEnumString() {
        User saved = userRepository.saveAndFlush(new User("admin@example.com", Role.ADMIN));

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);

        String rawValue = jdbcTemplate.queryForObject(
            "SELECT role FROM users WHERE id = ?", String.class, saved.getId());
        assertThat(rawValue).isEqualTo("ADMIN");
    }
}
