ALTER TABLE runner_language_installations
    ADD COLUMN install_stage VARCHAR(60),
    ADD COLUMN last_error_code VARCHAR(80);
