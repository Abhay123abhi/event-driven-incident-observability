-- Persist Alertmanager startsAt so delayed resolution cannot close a newer episode.
-- NULL for historical rows and legacy webhooks without startsAt.
ALTER TABLE incidents ADD COLUMN alert_starts_at TIMESTAMPTZ;
