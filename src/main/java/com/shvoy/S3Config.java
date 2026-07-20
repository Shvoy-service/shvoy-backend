package com.shvoy;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
class S3Config {

    @Bean
    @Profile("local")
    S3Client localS3Client(@Value("${aws.region}") String region,
                            @Value("${aws.s3.endpoint-override}") String endpointOverride) {
        return S3Client.builder()
            .region(Region.of(region))
            .endpointOverride(URI.create(endpointOverride))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .forcePathStyle(true)
            .build();
    }

    @Bean
    @Profile("!local")
    S3Client s3Client(@Value("${aws.region}") String region) {
        return S3Client.builder()
            .region(Region.of(region))
            .build();
    }
}
