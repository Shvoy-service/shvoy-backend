package com.shvoy;

/**
 * The outcome of an email send attempt (Story 9.4, 9.6 folded in). The
 * permanent/transient split is deliberate — it's the data a future retry story
 * reads without this story building retries.
 * <ul>
 *   <li>{@code SENT} — SES accepted the message (a message id was returned).</li>
 *   <li>{@code FAILED_PERMANENT} — won't succeed on retry: an unverified/rejected
 *       recipient (the sandbox case), an unverified sending domain, a suspended
 *       account, invalid content.</li>
 *   <li>{@code FAILED_TRANSIENT} — might succeed later: a throttle, a limit, a
 *       paused account, a network blip.</li>
 * </ul>
 */
public enum SendOutcome {
    SENT,
    FAILED_PERMANENT,
    FAILED_TRANSIENT
}
