-- Story 7.4: who created the provisional GRN, and when. The receipt state itself
-- is the consignment's existing receipt_status (DOCUMENTS_PENDING ->
-- PROVISIONALLY_RECEIPTED); these record the actor/timestamp of that transition.
ALTER TABLE shipment_consignments ADD COLUMN provisionally_receipted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE shipment_consignments ADD COLUMN provisionally_receipted_by UUID REFERENCES users (id);
