package com.shvoy;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling support — the codebase's first (Story 8.2, the
 * container-fill reminder poll). Scheduling is a cross-cutting infrastructure
 * concern, so it lives at the root beside the other {@code @Configuration} beans
 * (SES, S3, CORS). Individual {@code @Scheduled} beans are profile-gated where
 * they shouldn't fire (e.g. the reminder scheduler is {@code @Profile("!test")}).
 */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
