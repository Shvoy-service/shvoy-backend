package com.shvoy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs via {@code mvn verify} (Failsafe), not {@code mvn test} — requires Docker.
 * Spins up its own LocalStack container, independent of docker-compose.
 */
@Testcontainers
class S3ConnectivityIT {

    @Container
    static final LocalStackContainer localstack =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
            .withServices(LocalStackContainer.Service.S3);

    @Test
    void uploadsAndDownloadsAFile() throws IOException {
        S3Client s3 = S3Client.builder()
            .endpointOverride(localstack.getEndpoint())
            .region(Region.of(localstack.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
            .forcePathStyle(true)
            .build();

        String bucket = "shvoy-it-test";
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());

        String key = "ping.txt";
        String content = "shvoy s3 connectivity check";
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromString(content, StandardCharsets.UTF_8));

        byte[] downloaded = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .readAllBytes();

        assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo(content);
    }
}
