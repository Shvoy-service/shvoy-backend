ALTER TABLE companies ADD COLUMN registered_address VARCHAR(500);
ALTER TABLE companies ADD COLUMN country VARCHAR(100);
ALTER TABLE companies ADD COLUMN contact_email VARCHAR(255);
ALTER TABLE companies ADD COLUMN contact_phone VARCHAR(50);
ALTER TABLE companies ADD COLUMN registration_number VARCHAR(100);
ALTER TABLE companies ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
