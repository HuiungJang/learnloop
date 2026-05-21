ALTER TABLE source_bundles
    DROP CONSTRAINT source_bundles_kind_check;

ALTER TABLE source_bundles
    ADD CONSTRAINT source_bundles_kind_check CHECK (
        source_kind IN ('code', 'diff', 'commit', 'pull_request', 'conversation', 'supporting_context', 'local_ai_session')
    );

ALTER TABLE source_bundles
    ADD COLUMN auto_attribution VARCHAR(40) NOT NULL DEFAULT 'manual_or_unknown',
    ADD COLUMN user_attribution VARCHAR(40),
    ADD COLUMN attribution_confidence NUMERIC(6, 5),
    ADD COLUMN attribution_reasons_json TEXT NOT NULL DEFAULT '[]',
    ADD CONSTRAINT source_bundles_auto_attribution_check CHECK (auto_attribution IN ('ai_assisted', 'manual_or_unknown')),
    ADD CONSTRAINT source_bundles_user_attribution_check CHECK (
        user_attribution IS NULL OR user_attribution IN ('use_for_generation', 'manual', 'delete')
    ),
    ADD CONSTRAINT source_bundles_attribution_confidence_check CHECK (
        attribution_confidence IS NULL OR (attribution_confidence >= 0 AND attribution_confidence <= 1)
    );

ALTER TABLE evidence_items
    ADD COLUMN repo_relative_path VARCHAR(1000),
    ADD COLUMN size_bytes BIGINT,
    ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN content_truncated BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN limit_reason VARCHAR(160),
    ADD CONSTRAINT evidence_items_size_bytes_check CHECK (size_bytes IS NULL OR size_bytes >= 0),
    ADD CONSTRAINT evidence_items_repo_relative_path_check CHECK (
        repo_relative_path IS NULL OR length(trim(repo_relative_path)) > 0
    );

CREATE TABLE source_bundle_attribution_events (
    id VARCHAR(80) PRIMARY KEY,
    bundle_id VARCHAR(80) NOT NULL REFERENCES source_bundles(id),
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    actor_user_id VARCHAR(80) REFERENCES users(id),
    event_type VARCHAR(80) NOT NULL,
    auto_attribution VARCHAR(40),
    user_attribution VARCHAR(40),
    attribution_confidence NUMERIC(6, 5),
    attribution_reasons_json TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT source_bundle_attribution_events_type_check CHECK (event_type IN ('auto_detected', 'user_override')),
    CONSTRAINT source_bundle_attribution_events_auto_check CHECK (
        auto_attribution IS NULL OR auto_attribution IN ('ai_assisted', 'manual_or_unknown')
    ),
    CONSTRAINT source_bundle_attribution_events_user_check CHECK (
        user_attribution IS NULL OR user_attribution IN ('use_for_generation', 'manual', 'delete')
    ),
    CONSTRAINT source_bundle_attribution_events_confidence_check CHECK (
        attribution_confidence IS NULL OR (attribution_confidence >= 0 AND attribution_confidence <= 1)
    )
);

CREATE INDEX idx_source_bundle_attribution_events_bundle_created
    ON source_bundle_attribution_events (bundle_id, created_at DESC);

ALTER TABLE generation_runs
    ADD COLUMN source_bundle_ids_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN evidence_item_ids_json TEXT NOT NULL DEFAULT '[]';
