ALTER TABLE reconciliations ADD COLUMN outcome VARCHAR(30);
ALTER TABLE reconciliations ADD COLUMN tolerance_applied NUMERIC(5, 2);
