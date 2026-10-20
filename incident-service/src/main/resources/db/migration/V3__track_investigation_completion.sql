-- Resolution and investigation completion are independent lifecycle facts.
-- Persist only the completion timestamp; do not duplicate raw telemetry or store a second state machine.
ALTER TABLE incidents ADD COLUMN investigated_at TIMESTAMPTZ;
UPDATE incidents
SET investigated_at = COALESCE(updated_at, detected_at)
WHERE status = 'INVESTIGATED'
   OR (status = 'RESOLVED' AND jsonb_array_length(evidence) > 0);
