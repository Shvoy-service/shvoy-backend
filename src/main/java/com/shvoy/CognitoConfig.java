package com.shvoy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

@Configuration
@Profile("!local & !test")
class CognitoConfig {

    @Bean
    CognitoIdentityProviderClient cognitoIdentityProviderClient(@Value("${aws.region}") String region) {
        return CognitoIdentityProviderClient.builder()
            .region(Region.of(region))
            .build();
    }
}
