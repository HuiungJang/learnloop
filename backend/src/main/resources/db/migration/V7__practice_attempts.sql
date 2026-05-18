ALTER TABLE submissions
    ADD COLUMN client_attempt_id VARCHAR(120),
    ADD COLUMN asset_revision VARCHAR(120),
    ADD COLUMN language VARCHAR(40),
    ADD COLUMN attempt_status VARCHAR(40) NOT NULL DEFAULT 'submitted',
    ADD COLUMN score INTEGER,
    ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN submitted_at TIMESTAMPTZ;

UPDATE submissions
SET submitted_at = created_at
WHERE submitted_at IS NULL;

ALTER TABLE submissions
    ADD CONSTRAINT submissions_language_check CHECK (language IS NULL OR language IN ('typescript', 'kotlin', 'java')),
    ADD CONSTRAINT submissions_attempt_status_check CHECK (attempt_status IN ('draft', 'submitted')),
    ADD CONSTRAINT submissions_score_check CHECK (score IS NULL OR score >= 0);

CREATE UNIQUE INDEX ux_submissions_user_problem_client_attempt
    ON submissions (user_id, problem_id, client_attempt_id)
    WHERE client_attempt_id IS NOT NULL;

CREATE INDEX idx_submissions_user_problem_updated ON submissions (user_id, problem_id, updated_at DESC);
