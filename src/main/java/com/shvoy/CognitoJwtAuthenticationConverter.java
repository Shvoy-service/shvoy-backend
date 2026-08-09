package com.shvoy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Bridges a validated Cognito JWT (signature/issuer/expiry already checked
 * by the JwtDecoder — see SecurityConfig) to the SHVOY profile it belongs
 * to. Cognito authenticates; this resolves what SHVOY itself knows about
 * that identity — company_id (feeds TenantContext, via TenantContextFilter)
 * and role (feeds @PreAuthorize) — and rejects tokens for identities SHVOY
 * doesn't recognize as an active member of a company, independently of
 * whether Cognito still considers the token valid. That's what makes a
 * deactivated user's still-valid Cognito token unusable: TeamManagementService
 * .deactivate() only ever touches this table, never Cognito itself.
 *
 * Raw JDBC rather than UserRepository, same reason as RegistrationService/
 * InvitationService: this runs before any tenant is known, so a JPA query
 * (which needs a resolvable tenant to even open a Hibernate session) can't
 * be used here.
 */
@Component
@Profile("!local & !test")
class CognitoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String USER_ID_CLAIM = "shvoy_user_id";
    static final String COMPANY_ID_CLAIM = "shvoy_company_id";

    private final JdbcTemplate jdbcTemplate;

    CognitoJwtAuthenticationConverter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                "SELECT id, company_id, role, status FROM users WHERE cognito_sub = ?", jwt.getSubject());
        } catch (EmptyResultDataAccessException e) {
            throw invalidToken();
        }
        if (!"ACTIVE".equals(row.get("status"))) {
            throw invalidToken();
        }

        UUID userId = (UUID) row.get("id");
        UUID companyId = (UUID) row.get("company_id");
        String role = (String) row.get("role");

        Jwt enriched = Jwt.withTokenValue(jwt.getTokenValue())
            .headers(headers -> headers.putAll(jwt.getHeaders()))
            .claims(claims -> claims.putAll(jwt.getClaims()))
            .claim(USER_ID_CLAIM, userId.toString())
            .claim(COMPANY_ID_CLAIM, companyId.toString())
            .build();

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return new JwtAuthenticationToken(enriched, authorities);
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(
            new OAuth2Error("invalid_token", "No active SHVOY profile for this identity", null));
    }
}
