CREATE TABLE submission_files (
    id VARCHAR(80) PRIMARY KEY,
    submission_id VARCHAR(80) NOT NULL REFERENCES submissions(id),
    path VARCHAR(240) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT submission_files_path_not_blank CHECK (length(trim(path)) > 0)
);

CREATE UNIQUE INDEX ux_submission_files_submission_path ON submission_files (submission_id, path);

CREATE TABLE sandbox_run_results (
    id VARCHAR(80) PRIMARY KEY,
    problem_id VARCHAR(80) NOT NULL REFERENCES problems(id),
    user_id VARCHAR(80) NOT NULL REFERENCES users(id),
    submission_id VARCHAR(80) REFERENCES submissions(id),
    status VARCHAR(40) NOT NULL,
    runner_kind VARCHAR(80) NOT NULL,
    duration_ms BIGINT,
    tests_json TEXT NOT NULL DEFAULT '[]',
    stdout_excerpt TEXT,
    stderr_excerpt TEXT,
    failed_diff TEXT,
    failure_reason VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sandbox_run_results_status_check CHECK (
        status IN ('passed', 'failed', 'compile_error', 'timeout', 'resource_limited', 'runner_unavailable')
    ),
    CONSTRAINT sandbox_run_results_duration_check CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT sandbox_run_results_stdout_bounded CHECK (stdout_excerpt IS NULL OR octet_length(stdout_excerpt) <= 16384),
    CONSTRAINT sandbox_run_results_stderr_bounded CHECK (stderr_excerpt IS NULL OR octet_length(stderr_excerpt) <= 16384)
);

CREATE INDEX idx_sandbox_run_results_submission_created ON sandbox_run_results (submission_id, created_at DESC);
CREATE INDEX idx_sandbox_run_results_problem_user_created ON sandbox_run_results (problem_id, user_id, created_at DESC);
