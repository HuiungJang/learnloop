CREATE TABLE problem_hints (
    id VARCHAR(80) PRIMARY KEY,
    problem_id VARCHAR(80) NOT NULL REFERENCES problems(id),
    reveal_order INTEGER NOT NULL,
    label VARCHAR(160) NOT NULL,
    content TEXT NOT NULL,
    reveal_policy VARCHAR(40) NOT NULL DEFAULT 'manual',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT problem_hints_reveal_order_positive CHECK (reveal_order > 0),
    CONSTRAINT problem_hints_label_not_blank CHECK (length(trim(label)) > 0),
    CONSTRAINT problem_hints_content_not_blank CHECK (length(trim(content)) > 0),
    CONSTRAINT problem_hints_reveal_policy_check CHECK (reveal_policy IN ('manual', 'after_run', 'after_submit', 'pattern_reveal'))
);

CREATE UNIQUE INDEX ux_problem_hints_problem_reveal_order ON problem_hints (problem_id, reveal_order);

CREATE TABLE problem_provenance_links (
    id VARCHAR(80) PRIMARY KEY,
    problem_id VARCHAR(80) NOT NULL REFERENCES problems(id),
    evidence_item_id VARCHAR(80) REFERENCES evidence_items(id),
    source_link_id VARCHAR(80) REFERENCES source_links(id),
    source_type VARCHAR(60) NOT NULL,
    source_label VARCHAR(240) NOT NULL,
    redacted_excerpt TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT problem_provenance_source_type_check CHECK (
        source_type IN ('code', 'diff', 'commit', 'pull_request', 'conversation', 'supporting_context', 'source_link', 'manual')
    ),
    CONSTRAINT problem_provenance_label_not_blank CHECK (length(trim(source_label)) > 0),
    CONSTRAINT problem_provenance_excerpt_not_blank CHECK (length(trim(redacted_excerpt)) > 0),
    CONSTRAINT problem_provenance_excerpt_bounded CHECK (char_length(redacted_excerpt) <= 2000)
);

CREATE INDEX idx_problem_provenance_problem_sort ON problem_provenance_links (problem_id, sort_order);
CREATE INDEX idx_problem_provenance_evidence ON problem_provenance_links (evidence_item_id);
CREATE INDEX idx_problem_provenance_source_link ON problem_provenance_links (source_link_id);
