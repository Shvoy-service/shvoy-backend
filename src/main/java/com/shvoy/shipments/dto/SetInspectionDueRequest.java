package com.shvoy.shipments.dto;

/**
 * Set or clear a consignment's inspection-due flag (Story 7.4 revised). Clearing
 * a flag that was set waives a control, so a reason is required then (enforced in
 * the service — it depends on the current state).
 */
public record SetInspectionDueRequest(
    boolean due,
    String reason
) {
}
