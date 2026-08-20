package com.shvoy.containerfill.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

/**
 * The container-fill reminder email's wording (Story 8.2) — the 5th consumer of
 * the shared EmailSender seam. Enough to triage from the inbox: which container,
 * the spare CBM, the supplier, the decision deadline, and the link.
 *
 * <p>The deadline is rendered in <strong>Europe/London</strong> with the zone
 * label — DST-aware zone rules, never a fixed offset (the first {@code ZoneId} in
 * the codebase; emails have no browser, so the composer does the conversion the
 * frontend does elsewhere). See docs/CONTRACT.md "Dates and timestamps".
 */
@Component
class ContainerFillReminderEmailComposer {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter DEADLINE_FORMAT =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.UK);

    private final FrontendLinks frontendLinks;

    ContainerFillReminderEmailComposer(FrontendLinks frontendLinks) {
        this.frontendLinks = frontendLinks;
    }

    EmailContent compose(BigDecimal spareCbm, String supplierName, String blReference, Instant deadline, UUID offerId) {
        String container = blReference == null || blReference.isBlank()
            ? "(bill of lading not yet issued)"
            : blReference;
        String londonDeadline = DEADLINE_FORMAT.format(ZonedDateTime.ofInstant(deadline, LONDON)) + " (Europe/London)";

        String body = "A supplier has flagged spare capacity on a container and a decision is due.\n\n"
            + "Container: " + container + "\n"
            + "Spare capacity: " + spareCbm + " CBM\n"
            + "Supplier: " + supplierName + "\n"
            + "Decision deadline: " + londonDeadline + "\n\n"
            + "Review the offer and decide — fill it, or ship without:\n"
            + frontendLinks.containerFillOffer(offerId) + "\n\n"
            + "— SHVOY";

        return new EmailContent("Container-fill decision due — " + supplierName + " (" + spareCbm + " CBM)", body);
    }
}
