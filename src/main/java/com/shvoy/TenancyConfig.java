package com.shvoy;

import java.util.UUID;

import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
class TenancyConfig {

    @Bean
    CurrentTenantIdentifierResolver<UUID> currentTenantIdentifierResolver() {
        return new CurrentTenantIdentifierResolver<>() {
            @Override
            public UUID resolveCurrentTenantIdentifier() {
                return TenantContext.get();
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return true;
            }
        };
    }

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(
            CurrentTenantIdentifierResolver<UUID> resolver) {
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }

    /**
     * Registered manually (rather than as a {@code @Component}) so it runs
     * exactly once, at a defined order after Spring Security's filter chain
     * (default order -100).
     */
    @Bean
    FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(
            @Value("${tenancy.local.default-company-id:}") String localDefaultCompanyId,
            Environment environment) {
        UUID defaultCompanyId = localDefaultCompanyId.isBlank() ? null : UUID.fromString(localDefaultCompanyId);
        boolean honorDebugHeader = environment.acceptsProfiles(Profiles.of("local", "test"));
        FilterRegistrationBean<TenantContextFilter> registration =
            new FilterRegistrationBean<>(new TenantContextFilter(defaultCompanyId, honorDebugHeader));
        registration.setOrder(0);
        return registration;
    }
}
