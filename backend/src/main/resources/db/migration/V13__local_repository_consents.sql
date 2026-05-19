CREATE TABLE local_repository_consents (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    repo_identity_hash VARCHAR(128) NOT NULL,
    display_label VARCHAR(240) NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_by_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT local_repository_consents_status_check CHECK (status IN ('approved', 'revoked', 'always_ignored', 'missing')),
    CONSTRAINT local_repository_consents_unique_repo UNIQUE (organization_id, repo_identity_hash)
);

CREATE INDEX idx_local_repository_consents_org_status
    ON local_repository_consents (organization_id, status, updated_at DESC);

CREATE INDEX idx_source_bundles_org_deleted_created_id
    ON source_bundles (organization_id, deleted_at, created_at DESC, id DESC);

CREATE INDEX idx_source_bundles_org_deleted_scope_created_id
    ON source_bundles (organization_id, deleted_at, team_id, project_id, created_at DESC, id DESC);
