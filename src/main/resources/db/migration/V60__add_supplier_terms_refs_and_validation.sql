-- Supplier remodel: current/target term references, the validation lifecycle,
-- bank details (sensitive — masked by default, full read is FINANCE/ADMIN only),
-- and compliance status.
ALTER TABLE suppliers ADD COLUMN current_term_id UUID REFERENCES payment_terms (id);
ALTER TABLE suppliers ADD COLUMN target_term_id UUID REFERENCES payment_terms (id);
-- DEFAULT 'PENDING' so any insert that omits it (and the app always sets it via
-- the entity) lands PENDING; the UPDATE below overrides for already-ordered suppliers.
ALTER TABLE suppliers ADD COLUMN validation_status VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE suppliers ADD COLUMN bank_account_name VARCHAR(255);
ALTER TABLE suppliers ADD COLUMN bank_account_number VARCHAR(50);
ALTER TABLE suppliers ADD COLUMN bank_sort_code VARCHAR(20);
ALTER TABLE suppliers ADD COLUMN compliance_status VARCHAR(20);

-- Each migrated current term reused the supplier's id (V59), so a supplier that
-- had terms has current_term_id = its own id.
UPDATE suppliers SET current_term_id = id WHERE id IN (SELECT supplier_id FROM payment_terms);

-- Validation-default choice (documented): suppliers already ordered from migrate
-- as VALIDATED — they've been de-facto ordered from, and retro-blocking them would
-- break live flows; suppliers without any PO migrate PENDING. New suppliers start
-- PENDING (the entity default).
UPDATE suppliers SET validation_status =
    CASE WHEN EXISTS (SELECT 1 FROM purchase_orders po WHERE po.supplier_id = suppliers.id)
         THEN 'VALIDATED' ELSE 'PENDING' END;

ALTER TABLE suppliers ALTER COLUMN validation_status SET NOT NULL;
