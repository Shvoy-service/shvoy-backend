-- Story 7.3: soft-delete flag for a mis-linked co-loaded consignment. A detach
-- retains the row and its audit trail (evidence is never destroyed); detached
-- consignments are excluded from the active set. Defaulted false for existing rows.
ALTER TABLE shipment_consignments ADD COLUMN detached BOOLEAN NOT NULL DEFAULT FALSE;
