package com.shvoy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Endpoints reachable by a caller with no account yet, so none of them
     * can depend on authentication or an established tenant — see
     * RegistrationController and InviteAcceptanceController.
     */
    private static final String[] TENANT_EXEMPT_ENDPOINTS = {
        "/api/onboarding/register", "/api/onboarding/activate", "/api/onboarding/invite/accept"
    };

    /**
     * Mirrors onboarding.domain.Role's values. Listed as literals rather than
     * derived from the enum so this shared/root class doesn't depend back on
     * a feature module (onboarding already depends on root code — TenantScoped,
     * TenantGuard — so the reverse dependency would create a module cycle).
     * Keep in sync with Role if roles ever change.
     */
    private static final String[] ALL_ROLE_AUTHORITIES = {
        "ROLE_ADMIN", "ROLE_PURCHASING", "ROLE_FINANCE", "ROLE_APPROVER", "ROLE_READ_ONLY"
    };

    /**
     * Method security (e.g. {@code @PreAuthorize("hasRole('ADMIN')")}) is enabled
     * globally and runs regardless of profile, independently of the permitAll
     * filter chains below. Under local/test there's no real authentication yet
     * (test shares this chain so the existing onboarding controller tests, which
     * call protected endpoints with no auth setup, don't need rewriting for real
     * JWT enforcement — that's exercised separately, see CognitoJwtAuthenticationConverter's
     * own unit test), so the anonymous principal is granted every role's authority
     * here, making @PreAuthorize checks a no-op under these profiles only. This is
     * NOT a bypass of security generally — dev/prod get no such grant, so
     * @PreAuthorize checks are live there via the real authenticated principal
     * built below.
     */
    @Bean
    @Profile("local | test")
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .anonymous(anon -> anon.authorities(ALL_ROLE_AUTHORITIES));
        return http.build();
    }

    /**
     * dev/prod filter chain: validates Cognito-issued JWTs as an OAuth2
     * resource server. cognitoJwtDecoder verifies signature (via the pool's
     * JWKS), issuer, expiry, and — since Cognito access tokens carry a
     * client_id claim rather than the standard aud — that the token was
     * issued for this app client specifically; CognitoJwtAuthenticationConverter
     * then resolves the SHVOY profile behind the token's subject and rejects
     * anything that isn't an ACTIVE member of a company (see its own Javadoc
     * for why that also covers INACTIVE/deactivated users, closing what used
     * to be a TODO here).
     */
    @Bean
    @Profile("!local & !test")
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, JwtDecoder cognitoJwtDecoder,
            Converter<Jwt, AbstractAuthenticationToken> cognitoJwtAuthenticationConverter) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(TENANT_EXEMPT_ENDPOINTS).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                .decoder(cognitoJwtDecoder)
                .jwtAuthenticationConverter(cognitoJwtAuthenticationConverter)))
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Profile("!local & !test")
    JwtDecoder cognitoJwtDecoder(@Value("${aws.region}") String region,
            @Value("${cognito.user-pool-id}") String userPoolId,
            @Value("${cognito.app-client-id}") String appClientId) {
        String issuerUri = "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuerUri), new CognitoClientIdValidator(appClientId));
        decoder.setJwtValidator(validators);
        return decoder;
    }

    /**
     * Cognito access tokens identify their app client via a {@code client_id}
     * claim, not the standard {@code aud} — the default issuer/timestamp
     * validators JwtValidators.createDefaultWithIssuer builds don't check
     * this, so without this validator a token issued for ANY app client in
     * the user pool would be accepted here, not just SHVOY's.
     */
    private static final class CognitoClientIdValidator implements OAuth2TokenValidator<Jwt> {

        private final String expectedClientId;

        CognitoClientIdValidator(String expectedClientId) {
            this.expectedClientId = expectedClientId;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            if (expectedClientId.equals(token.getClaimAsString("client_id"))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Token was not issued for this app client", null));
        }
    }
}
