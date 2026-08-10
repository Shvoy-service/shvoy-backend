package com.shvoy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Origins are entirely config-driven (shvoy.cors.allowed-origins, see each
 * application-*.yml) rather than hardcoded here — dev/prod values aren't
 * even known yet (Cloudflare Pages preview domains, the eventual prod
 * domain), so this class must not need a code change once they are.
 *
 * Uses allowedOriginPatterns rather than allowedOrigins: preview deploys use
 * per-PR Cloudflare Pages subdomains, so dev's config will eventually need a
 * wildcard pattern (e.g. {@code https://*.pages.dev}) — allowedOrigins
 * doesn't support wildcards at all, and neither supports them combined with
 * allowCredentials(true). That's moot here since credentials are off (see
 * below), but patterns are still what makes a wildcard subdomain expressible
 * at all.
 *
 * allowCredentials is false: the Cognito access token travels in the
 * Authorization header, not a cookie, so the browser never needs to send
 * credentials for this API. Flagged explicitly rather than left on the
 * default, since turning it on is a meaningful relaxation to reconsider
 * deliberately if a cookie-based flow is ever introduced.
 *
 * Two beans, split the same way as SecurityConfig's two filter chains:
 * local/test additionally allow X-Debug-Company-Id, since that's the only
 * place TenantContextFilter honors it at all (see its Javadoc) — allowing it
 * elsewhere would advertise a mechanism that doesn't do anything there.
 */
@Configuration
class CorsConfig {

    private static final List<String> BASE_ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "X-Correlation-Id");
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Bean
    @Profile("local | test")
    CorsConfigurationSource localCorsConfigurationSource(
            @Value("${shvoy.cors.allowed-origins}") List<String> allowedOrigins) {
        List<String> allowedHeaders = new ArrayList<>(BASE_ALLOWED_HEADERS);
        allowedHeaders.add("X-Debug-Company-Id");
        return buildSource(allowedOrigins, allowedHeaders);
    }

    @Bean
    @Profile("!local & !test")
    CorsConfigurationSource defaultCorsConfigurationSource(
            @Value("${shvoy.cors.allowed-origins}") List<String> allowedOrigins) {
        return buildSource(allowedOrigins, BASE_ALLOWED_HEADERS);
    }

    private static CorsConfigurationSource buildSource(List<String> allowedOrigins, List<String> allowedHeaders) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(allowedHeaders);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
