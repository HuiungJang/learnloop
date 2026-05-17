CREATE TABLE organizations (
    id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE teams (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

CREATE TABLE projects (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    team_id VARCHAR(80) REFERENCES teams(id),
    name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

CREATE TABLE users (
    id VARCHAR(80) PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    team_id VARCHAR(80) REFERENCES teams(id),
    project_id VARCHAR(80) REFERENCES projects(id),
    role VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT memberships_role_check CHECK (role IN ('learner', 'contributor', 'reviewer', 'admin'))
);

CREATE UNIQUE INDEX ux_memberships_scope
    ON memberships (
        user_id,
        organization_id,
        role,
        COALESCE(team_id, ''),
        COALESCE(project_id, '')
    );

CREATE INDEX idx_memberships_user_org ON memberships (user_id, organization_id);
CREATE INDEX idx_memberships_org_role ON memberships (organization_id, role);

CREATE TABLE session_tokens (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_session_tokens_token_hash ON session_tokens (token_hash);
CREATE INDEX idx_session_tokens_user_expires ON session_tokens (user_id, expires_at);
