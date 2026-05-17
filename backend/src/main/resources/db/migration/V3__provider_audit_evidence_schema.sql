CREATE TABLE ai_providers (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    owner_user_id VARCHAR(80) REFERENCES users(id),
    created_by_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    provider VARCHAR(80) NOT NULL,
    model VARCHAR(160) NOT NULL,
    scope VARCHAR(40) NOT NULL,
    auth_type VARCHAR(40) NOT NULL,
    retention_mode VARCHAR(40) NOT NULL,
    credential_ref VARCHAR(160) NOT NULL,
    credential_fingerprint VARCHAR(64) NOT NULL,
    secret_preview VARCHAR(32),
    status VARCHAR(40) NOT NULL,
    org_approved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ai_providers_scope_check CHECK (scope IN ('organization', 'personal')),
    CONSTRAINT ai_providers_status_check CHECK (status IN ('active', 'disabled', 'revoked'))
);

CREATE INDEX idx_ai_providers_org_scope ON ai_providers (organization_id, scope);
CREATE INDEX idx_ai_providers_owner ON ai_providers (owner_user_id);

CREATE TABLE audit_logs (
    id VARCHAR(80) PRIMARY KEY,
    actor_user_id VARCHAR(80) REFERENCES users(id),
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    event_type VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(80) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    metadata_json TEXT NOT NULL,
    previous_hash CHAR(64),
    event_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_org_created ON audit_logs (organization_id, created_at DESC);

CREATE TABLE source_bundles (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    team_id VARCHAR(80) REFERENCES teams(id),
    project_id VARCHAR(80) REFERENCES projects(id),
    created_by_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    title VARCHAR(240) NOT NULL,
    source_kind VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    repository_url VARCHAR(500),
    pull_request_url VARCHAR(500),
    commit_sha VARCHAR(80),
    branch_name VARCHAR(240),
    file_paths_json TEXT NOT NULL,
    provenance_json TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    secret_findings_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT source_bundles_kind_check CHECK (
        source_kind IN ('code', 'diff', 'commit', 'pull_request', 'conversation', 'supporting_context')
    ),
    CONSTRAINT source_bundles_status_check CHECK (status IN ('ready', 'blocked_sensitive'))
);

CREATE INDEX idx_source_bundles_org_status ON source_bundles (organization_id, status);
CREATE INDEX idx_source_bundles_content_hash ON source_bundles (organization_id, content_hash);

CREATE TABLE evidence_items (
    id VARCHAR(80) PRIMARY KEY,
    bundle_id VARCHAR(80) NOT NULL REFERENCES source_bundles(id),
    item_type VARCHAR(60) NOT NULL,
    content_text TEXT,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_evidence_items_bundle ON evidence_items (bundle_id);
