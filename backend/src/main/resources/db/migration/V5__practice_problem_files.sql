CREATE TABLE problem_files (
    id VARCHAR(80) PRIMARY KEY,
    problem_id VARCHAR(80) NOT NULL REFERENCES problems(id),
    path VARCHAR(240) NOT NULL,
    language VARCHAR(40) NOT NULL,
    file_role VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    read_only BOOLEAN NOT NULL DEFAULT false,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT problem_files_language_check CHECK (language IN ('typescript', 'kotlin', 'java', 'json', 'markdown', 'text')),
    CONSTRAINT problem_files_role_check CHECK (file_role IN ('starter', 'test', 'support', 'solution', 'hidden_test')),
    CONSTRAINT problem_files_path_not_blank CHECK (length(trim(path)) > 0)
);

CREATE UNIQUE INDEX ux_problem_files_problem_path ON problem_files (problem_id, path);

CREATE INDEX idx_problem_files_problem_sort ON problem_files (problem_id, sort_order);
