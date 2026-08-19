package com.shvoy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists {@link SendRecord} entries (Story 9.4). {@code REQUIRES_NEW} so the
 * record commits in its own transaction — independent of the business action that
 * triggered the send (the record is the truth about the attempt regardless of
 * whether the caller's transaction later rolls back) and of any active tx. One
 * nested level, at email volumes — well within the pool.
 *
 * <p>Recording is itself best-effort: a failure to write the record must never
 * break the send or the business action, so the writer swallows and logs (the
 * whole point of the send being fire-and-forget).
 */
@Service
public class SendRecordService {

    private static final Logger log = LoggerFactory.getLogger(SendRecordService.class);

    private final SendRecordRepository repository;

    SendRecordService(SendRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(EmailSource source, String recipient, String subject, SendOutcome outcome,
            String sesMessageId, String error) {
        // Never the body — subject + metadata only (token hygiene).
        repository.save(new SendRecord(source, recipient, subject, outcome, sesMessageId, truncate(error), null));
    }

    /** Overload carrying the triggering entity reference where the caller has one. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(EmailSource source, String recipient, String subject, SendOutcome outcome,
            String sesMessageId, String error, String entityReference) {
        repository.save(new SendRecord(source, recipient, subject, outcome, sesMessageId, truncate(error),
            entityReference));
    }

    /** Best-effort: a record-write failure must not propagate to the send or the business action. */
    public void recordSafely(EmailSource source, String recipient, String subject, SendOutcome outcome,
            String sesMessageId, String error, String entityReference) {
        try {
            record(source, recipient, subject, outcome, sesMessageId, error, entityReference);
        } catch (RuntimeException e) {
            log.warn("Failed to write send record for {} to {} ({}) — swallowing", source, recipient, outcome, e);
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 2000 ? error.substring(0, 2000) : error;
    }
}
