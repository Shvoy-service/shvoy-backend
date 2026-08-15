-- Invoice remodel: many invoices per PO, each declaring what it covers.
-- covers_type is mandatory; existing (one-per-PO) invoices migrate to AMOUNT —
-- what the data actually says (no coverage was ever captured), NOT what it
-- probably meant. They're flagged for manual reclassification by being AMOUNT
-- (the weakest signal). DEFAULT 'AMOUNT' also keeps JDBC-seeded test rows valid.
-- covers_consignment_id: the receipted consignment a SHIPMENT invoice ties to.
-- supersedes_invoice_id: the specific invoice a correction replaces (one logical
-- invoice = one chain; supersession now corrects one invoice, not "the PO's").
ALTER TABLE invoices ADD COLUMN covers_type VARCHAR(20) DEFAULT 'AMOUNT';
UPDATE invoices SET covers_type = 'AMOUNT' WHERE covers_type IS NULL;
ALTER TABLE invoices ALTER COLUMN covers_type SET NOT NULL;
ALTER TABLE invoices ADD COLUMN covers_consignment_id UUID;
ALTER TABLE invoices ADD COLUMN supersedes_invoice_id UUID REFERENCES invoices (id);
