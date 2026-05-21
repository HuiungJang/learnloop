ALTER TABLE source_bundles
    DROP CONSTRAINT source_bundles_status_check;

ALTER TABLE source_bundles
    ADD CONSTRAINT source_bundles_status_check CHECK (
        status IN (
            'ready',
            'blocked_sensitive',
            'received',
            'rejected_by_path_or_size',
            'scanned_clean',
            'quarantined_secret',
            'redacted_stored',
            'generation_eligible',
            'purged_raw',
            'deleted'
        )
    );

ALTER TABLE source_bundles
    ADD COLUMN dedupe_key CHAR(64);

CREATE UNIQUE INDEX idx_source_bundles_local_session_dedupe
    ON source_bundles (organization_id, source_kind, dedupe_key)
    WHERE dedupe_key IS NOT NULL AND deleted_at IS NULL;
