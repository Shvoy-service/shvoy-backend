package com.shvoy.dashboard.dto;

/**
 * A system alert's level (Story 9.3) — two levels, deliberately, not five:
 * nothing on this banner is a paging emergency. {@code WARNING} = a state a user
 * should fix; {@code INFO} = worth knowing. The banner reads current state; it
 * never escalates.
 */
public enum AlertSeverity {
    WARNING,
    INFO
}
