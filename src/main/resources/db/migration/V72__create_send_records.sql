-- Story 9.4 (with 9.6 folded in) — the append-only record of every email send
-- attempt: which flow, recipient, subject, outcome (SENT / FAILED_PERMANENT /
-- FAILED_TRANSIENT), SES message id or error, the triggering entity. Answers
-- "did the invite actually send?" and is the substrate a future retry reads.
-- NEVER stores the email body (bodies carry invite links — token hygiene).
CREATE TABLE send_records (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    source VARCHAR(30) NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    subject VARCHAR(500),
    outcome VARCHAR(20) NOT NULL,
    ses_message_id VARCHAR(200),
    error VARCHAR(2000),
    entity_reference VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_send_records_company_id ON send_records (company_id);
