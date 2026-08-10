package com.shvoy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Basic metadata for the springdoc-generated spec (served at /v3/api-docs,
 * explorable at /swagger-ui.html — see SecurityConfig for where those are
 * permitted, and application-prod.yml for why they're disabled in prod).
 * Endpoint-level documentation is incremental; this just makes the spec
 * itself coherent.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI shvoyOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("SHVOY API")
                .description("SHVOY B2B procurement platform API")
                .version("0.0.1"));
    }
}
