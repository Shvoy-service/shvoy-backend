-- Story 6.5: the latest three-way-match failure detail (which leg, expected vs
-- actual), so Screen 6's side-by-side and 6.6's routing have it without
-- replaying the audit trail. Null when the match passed or the payment isn't
-- yet matchable (awaiting a leg).
ALTER TABLE payments ADD COLUMN match_detail VARCHAR(2000);
