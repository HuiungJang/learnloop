CREATE TABLE local_evidence_retention_settings (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL,
    owner_user_id VARCHAR(80) NOT NULL,
    retention_mode VARCHAR(40) NOT NULL,
    retention_days INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT local_evidence_retention_mode_check CHECK (retention_mode IN ('default', 'disabled', 'immediate')),
    CONSTRAINT local_evidence_retention_days_check CHECK (
        (retention_mode = 'default' AND retention_days BETWEEN 1 AND 3650)
        OR (retention_mode = 'disabled' AND retention_days IS NULL)
        OR (retention_mode = 'immediate' AND retention_days = 0)
    ),
    CONSTRAINT local_evidence_retention_owner_org_unique UNIQUE (organization_id, owner_user_id)
);
