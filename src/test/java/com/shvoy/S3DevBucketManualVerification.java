package com.shvoy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story A4's real deliverable: proving the only thing that differs between
 * {@code local} and {@code dev} is which {@link S3Client} bean Spring
 * selects (see S3Config), not any code. There's no document-handling
 * service yet to run this proof through (checked before writing this — see
 * docs/aws/A4-s3-document-bucket-checklist.md), so this exercises the exact
 * same client-construction shape as S3Config's {@code !local} bean
 * directly, against the real dev bucket, rather than going through a full
 * {@code @SpringBootTest} — a full "dev" profile Spring context would also
 * try to reach RDS and Cognito (see application-dev.yml), neither of which
 * is reachable from a laptop outside the VPC, and neither of which this
 * story is about.
 *
 * Deliberately named to avoid both Surefire's *Test.java and Failsafe's
 * *IT.java conventions, so neither `mvn test` nor `mvn verify` picks this
 * up automatically — it would fail for anyone without real AWS credentials
 * configured, which is everyone except whoever is actively running this
 * one-off verification.
 *
 * To run: configure local AWS credentials for the account (e.g.
 * `aws configure sso` against the Identity Center user from Story A1 — not
 * a long-lived access key), confirm the Story A4 bucket exists, then run
 * this single test from your IDE, or:
 *   mvn test -Dtest=S3DevBucketManualVerification -DfailIfNoTests=false
 */
class S3DevBucketManualVerification {

    private static final String BUCKET = "shvoy-documents-dev-038774852612-eu-west-2-an";
    private static final String REGION = "eu-west-2";

    @Test
    void uploadsAndDownloadsAgainstTheRealDevBucket() {
        // Exactly S3Config's `!local` bean: no endpoint override, no
        // hardcoded credentials — relies on the default AWS credential
        // chain (your local SSO session), same as the app does in dev/prod.
        S3Client s3 = S3Client.builder()
            .region(Region.of(REGION))
            .build();

        String key = "shvoy-a4-verification/" + java.util.UUID.randomUUID() + ".txt";
        String content = "shvoy A4 real-S3 verification";

        try {
            s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));

            byte[] downloaded = s3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                .readAllBytes();

            assertThat(new String(downloaded, StandardCharsets.UTF_8)).isEqualTo(content);
        } catch (Exception e) {
            throw new RuntimeException(
                "Real-S3 verification failed — see docs/aws/A4-s3-document-bucket-checklist.md Step 4. "
                    + "Common causes: no local AWS credentials configured, bucket name doesn't match "
                    + "(check application-dev.yml's aws.s3.documents-bucket), or the bucket doesn't exist yet.",
                e);
        } finally {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());
        }
    }
}
