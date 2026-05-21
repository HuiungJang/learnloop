CREATE TABLE runner_language_installations (
    language VARCHAR(40) PRIMARY KEY,
    desired_enabled BOOLEAN NOT NULL DEFAULT false,
    image_ref VARCHAR(240) NOT NULL,
    status VARCHAR(40) NOT NULL,
    installed_at TIMESTAMPTZ,
    last_checked_at TIMESTAMPTZ,
    last_error TEXT,
    CONSTRAINT runner_language_installations_status_check CHECK (
        status IN ('available', 'installing', 'installed', 'missing', 'failed')
    )
);

INSERT INTO runner_language_installations (language, desired_enabled, image_ref, status)
VALUES
    ('typescript', true, 'learnloop-runner-typescript:latest', 'missing'),
    ('kotlin', true, 'learnloop-runner-kotlin:latest', 'missing'),
    ('java', true, 'learnloop-runner-java:latest', 'missing'),
    ('swift', false, 'learnloop-runner-swift:latest', 'available'),
    ('rust', false, 'learnloop-runner-rust:latest', 'available');
