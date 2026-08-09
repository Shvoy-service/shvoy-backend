package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the JWT->SHVOY-profile bridge in isolation from real Cognito:
 * hand-built {@link Jwt}s in, a resolved authentication token or a thrown
 * exception out. The converter is instantiated directly rather than
 * autowired, since its bean is @Profile("!local & !test") and this suite
 * runs under "test" (see SecurityConfig's Javadoc for why "test"
 * deliberately stays on the permissive chain instead of exercising real
 * JWT enforcement end to end).
 */
@SpringBootTest
@ActiveProfiles("test")
class CognitoJwtAuthenticationConverterTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    CognitoJwtAuthenticationConverter converter;
    UUID companyId;
    UUID userId;

    @BeforeEach
    void setUp() {
        converter = new CognitoJwtAuthenticationConverter(jdbcTemplate);
        companyId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)",
            companyId, "Converter Test Co", Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyId);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyId);
    }

    @Test
    void activeProfileResolvesToAuthenticatedTokenCarryingRoleAndCompany() {
        String sub = seedUser("ADMIN", "ACTIVE");

        AbstractAuthenticationToken result = converter.convert(jwtFor(sub));

        assertThat(result).isInstanceOf(JwtAuthenticationToken.class);
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) result;
        assertThat(jwtAuth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
        assertThat(jwtAuth.getToken().getClaimAsString(CognitoJwtAuthenticationConverter.COMPANY_ID_CLAIM))
            .isEqualTo(companyId.toString());
        assertThat(jwtAuth.getToken().getClaimAsString(CognitoJwtAuthenticationConverter.USER_ID_CLAIM))
            .isEqualTo(userId.toString());
    }

    @Test
    void pendingProfileIsRejected() {
        String sub = seedUser("ADMIN", "PENDING");

        assertThatThrownBy(() -> converter.convert(jwtFor(sub)))
            .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void inactiveProfileIsRejected() {
        String sub = seedUser("ADMIN", "INACTIVE");

        assertThatThrownBy(() -> converter.convert(jwtFor(sub)))
            .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void unknownSubjectIsRejected() {
        assertThatThrownBy(() -> converter.convert(jwtFor(UUID.randomUUID().toString())))
            .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private String seedUser(String role, String status) {
        userId = UUID.randomUUID();
        String sub = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, cognito_sub) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            userId, sub + "@converter-test.example.com", role, status, Timestamp.from(Instant.now()),
            companyId, sub);
        return sub;
    }

    private static Jwt jwtFor(String subject) {
        return Jwt.withTokenValue("token-value")
            .header("alg", "RS256")
            .subject(subject)
            .claim("client_id", "test-client")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    }
}
