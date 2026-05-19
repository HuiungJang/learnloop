ALTER TABLE source_bundles
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by_user_id VARCHAR(80) REFERENCES users(id),
    ADD COLUMN deletion_reason VARCHAR(240);

ALTER TABLE evidence_items
    ADD COLUMN raw_purged_at TIMESTAMPTZ,
    ADD COLUMN raw_purge_reason VARCHAR(240);

CREATE INDEX idx_source_bundles_org_deleted ON source_bundles (organization_id, deleted_at);
CREATE INDEX idx_source_bundles_org_repo_deleted ON source_bundles (organization_id, repository_url, deleted_at);
