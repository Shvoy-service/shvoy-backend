package com.shvoy.containerfill.service;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The trigger for the container-fill reminder poll (Story 8.2) — deliberately
 * trivial: all logic is in {@link ContainerFillReminderPoll#runOnce()}, which tests
 * drive directly. {@code @Profile("!test")} so no poll fires during tests (keeping
 * them deterministic); {@code initialDelay} = the interval so it doesn't fire the
 * instant the context starts either.
 */
@Component
@Profile("!test")
class ContainerFillReminderScheduler {

    private final ContainerFillReminderPoll poll;

    ContainerFillReminderScheduler(ContainerFillReminderPoll poll) {
        this.poll = poll;
    }

    @Scheduled(
        initialDelayString = "${containerfill.reminder.poll-interval-ms:300000}",
        fixedDelayString = "${containerfill.reminder.poll-interval-ms:300000}")
    void pollForDueReminders() {
        poll.runOnce();
    }
}
