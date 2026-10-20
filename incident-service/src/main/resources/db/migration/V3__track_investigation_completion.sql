-- The recovery webhook may arrive before the asynchronous investigation finishes.
-- Keep only the completion timestamp; incident status continues to represent resolution.
ALTER TABLE incidents ADD COLUMN investigated_at TIMESTAMPTZ;
UPDATE incidents SET investigated_at = COALESCE(updated_at, detected_at)
WHERE status = 'INVESTIGATED'
   OR (status = 'RESOLVED' AND jsonb_array_length(evidence) > 0);
