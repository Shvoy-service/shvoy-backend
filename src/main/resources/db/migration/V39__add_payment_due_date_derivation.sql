ALTER TABLE payments ADD COLUMN anchor_event VARCHAR(20);
ALTER TABLE payments ADD COLUMN days_offset INTEGER;
ALTER TABLE payments ADD COLUMN anchor_date_applied DATE;
