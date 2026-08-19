package com.shvoy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * The SESv2 client bean (Story 9.4) — dev/prod only ({@code local}/{@code test}
 * use {@code ConsoleEmailSender}), same profile split and default-credentials
 * pattern as {@code CognitoConfig}: no explicit credentials provider, so the ECS
 * task role's scoped {@code ses:SendEmail} is used at runtime; no SMTP, no keys.
 */
@Configuration
@Profile("!local & !test")
class SesConfig {

    @Bean
    SesV2Client sesV2Client(@Value("${aws.region}") String region) {
        return SesV2Client.builder()
            .region(Region.of(region))
            .build();
    }
}
