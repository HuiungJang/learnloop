CREATE TABLE source_links (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    conversation_bundle_id VARCHAR(80) NOT NULL REFERENCES source_bundles(id),
    code_bundle_id VARCHAR(80) NOT NULL REFERENCES source_bundles(id),
    status VARCHAR(40) NOT NULL,
    confidence NUMERIC(6, 5) NOT NULL,
    created_by_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    decided_by_user_id VARCHAR(80) REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    CONSTRAINT source_links_status_check CHECK (status IN ('suggested', 'confirmed', 'rejected'))
);

CREATE INDEX idx_source_links_org_status ON source_links (organization_id, status);

CREATE TABLE generation_runs (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    provider_config_id VARCHAR(80) NOT NULL REFERENCES ai_providers(id),
    created_by_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    status VARCHAR(40) NOT NULL,
    visibility VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(120),
    failure_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT generation_runs_status_check CHECK (status IN ('completed', 'failed')),
    CONSTRAINT generation_runs_visibility_check CHECK (visibility IN ('private', 'organization'))
);

CREATE UNIQUE INDEX ux_generation_runs_idempotency
    ON generation_runs (organization_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE pattern_cards (
    id VARCHAR(80) PRIMARY KEY,
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    team_id VARCHAR(80) REFERENCES teams(id),
    project_id VARCHAR(80) REFERENCES projects(id),
    generation_run_id VARCHAR(80) REFERENCES generation_runs(id),
    created_by_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    title VARCHAR(240) NOT NULL,
    summary TEXT NOT NULL,
    visibility VARCHAR(40) NOT NULL,
    publication_status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT pattern_cards_visibility_check CHECK (visibility IN ('private', 'organization')),
    CONSTRAINT pattern_cards_publication_check CHECK (publication_status IN ('draft', 'published'))
);

CREATE INDEX idx_pattern_cards_org_status ON pattern_cards (organization_id, publication_status);

CREATE TABLE pattern_tags (
    id VARCHAR(80) PRIMARY KEY,
    tag_type VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(160) NOT NULL UNIQUE
);

CREATE TABLE pattern_tag_links (
    pattern_card_id VARCHAR(80) NOT NULL REFERENCES pattern_cards(id),
    tag_id VARCHAR(80) NOT NULL REFERENCES pattern_tags(id),
    PRIMARY KEY (pattern_card_id, tag_id)
);

CREATE TABLE problems (
    id VARCHAR(80) PRIMARY KEY,
    pattern_card_id VARCHAR(80) NOT NULL REFERENCES pattern_cards(id),
    problem_type VARCHAR(80) NOT NULL,
    prompt TEXT NOT NULL,
    reference_answer TEXT NOT NULL,
    difficulty VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_problems_card ON problems (pattern_card_id);

CREATE TABLE review_tasks (
    id VARCHAR(80) PRIMARY KEY,
    pattern_card_id VARCHAR(80) NOT NULL REFERENCES pattern_cards(id),
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    author_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    CONSTRAINT review_tasks_status_check CHECK (status IN ('open', 'approved', 'changes_requested', 'rejected'))
);

CREATE INDEX idx_review_tasks_org_status ON review_tasks (organization_id, status);

CREATE TABLE review_decisions (
    id VARCHAR(80) PRIMARY KEY,
    review_task_id VARCHAR(80) NOT NULL REFERENCES review_tasks(id),
    reviewer_user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    decision VARCHAR(40) NOT NULL,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT review_decisions_decision_check CHECK (decision IN ('approve', 'request_changes', 'reject'))
);

CREATE TABLE submissions (
    id VARCHAR(80) PRIMARY KEY,
    problem_id VARCHAR(80) NOT NULL REFERENCES problems(id),
    user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    text_answer TEXT NOT NULL,
    result_status VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_submissions_user_problem ON submissions (user_id, problem_id);

CREATE TABLE proficiency_scores (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    organization_id VARCHAR(80) NOT NULL REFERENCES organizations(id),
    tag_name VARCHAR(160) NOT NULL,
    score INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, organization_id, tag_name)
);
