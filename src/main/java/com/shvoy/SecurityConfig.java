package com.shvoy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /**
     * The one place in the app allowed to create a companies row. Both
     * endpoints act before any tenant/authentication exists — see
     * RegistrationController.
     */
    private static final String[] TENANT_EXEMPT_ENDPOINTS = {
        "/api/onboarding/register", "/api/onboarding/activate"
    };

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

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
     * filter chains below. Locally there's no real authentication yet, so the
     * anonymous principal is granted every role's authority here, making those
     * checks a no-op under this profile only. This is NOT a bypass of security
     * generally — dev/prod get no such grant, so @PreAuthorize checks are live
     * there as soon as a real authenticated principal exists.
     */
    @Bean
    @Profile("local")
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .anonymous(anon -> anon.authorities(ALL_ROLE_AUTHORITIES));
        return http.build();
    }

    /**
     * dev/prod filter chain. Permissive for now because Cognito user pools aren't
     * provisioned yet; this is the isolated point where an oauth2ResourceServer().jwt()
     * config gets wired in later without touching any business logic. The
     * register/activate endpoints are listed explicitly ahead of that catch-all
     * so they stay permitted once it's tightened to .anyRequest().authenticated() —
     * nothing will need to remember them at that point.
     */
    @Bean
    @Profile("!local")
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(TENANT_EXEMPT_ENDPOINTS).permitAll()
                .anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
