ALTER TABLE ai_providers
    ADD COLUMN base_url VARCHAR(500),
    ADD COLUMN credential_algorithm VARCHAR(40),
    ADD COLUMN credential_iv TEXT,
    ADD COLUMN credential_tag TEXT,
    ADD COLUMN credential_ciphertext TEXT;

ALTER TABLE ai_providers
    ADD CONSTRAINT ai_providers_credential_encryption_all_or_none CHECK (
        (
            credential_algorithm IS NULL
            AND credential_iv IS NULL
            AND credential_tag IS NULL
            AND credential_ciphertext IS NULL
        )
        OR
        (
            credential_algorithm IS NOT NULL
            AND credential_iv IS NOT NULL
            AND credential_tag IS NOT NULL
            AND credential_ciphertext IS NOT NULL
        )
    ),
    ADD CONSTRAINT ai_providers_credential_algorithm_check CHECK (
        credential_algorithm IS NULL OR credential_algorithm = 'AES_GCM_V1'
    );
