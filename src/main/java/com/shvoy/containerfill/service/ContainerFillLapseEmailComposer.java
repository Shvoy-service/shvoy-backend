package com.shvoy.containerfill.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

/**
 * The container-fill lapse email's wording (Story 8.3) — EmailSender consumer six.
 * Closes the loop the reminder opened: an offer's deadline passed undecided, so it
 * shipped without. Same audience as the reminder; reuses the offer-detail link
 * (no new frontend route).
 */
@Component
class ContainerFillLapseEmailComposer {

    private final FrontendLinks frontendLinks;

    ContainerFillLapseEmailComposer(FrontendLinks frontendLinks) {
        this.frontendLinks = frontendLinks;
    }

    EmailContent compose(BigDecimal spareCbm, String supplierName, String blReference, UUID offerId) {
        String container = blReference == null || blReference.isBlank()
            ? "(bill of lading not yet issued)"
            : blReference;

        String body = "A container-fill offer's decision deadline passed with no decision, so it lapsed — "
            + "the container shipped without the spare capacity being filled.\n\n"
            + "Container: " + container + "\n"
            + "Spare capacity offered: " + spareCbm + " CBM\n"
            + "Supplier: " + supplierName + "\n\n"
            + "The offer, for the record:\n"
            + frontendLinks.containerFillOffer(offerId) + "\n\n"
            + "— SHVOY";

        return new EmailContent("Container-fill offer lapsed — " + supplierName + " (" + spareCbm + " CBM)", body);
    }
}
