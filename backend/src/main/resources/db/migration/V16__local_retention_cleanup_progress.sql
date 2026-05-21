ALTER TABLE local_evidence_retention_settings
    ADD COLUMN last_cleanup_at TIMESTAMPTZ,
    ADD COLUMN last_cleanup_purged_items INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_cleanup_reclaimed_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_cleanup_remaining_items INTEGER NOT NULL DEFAULT 0;
