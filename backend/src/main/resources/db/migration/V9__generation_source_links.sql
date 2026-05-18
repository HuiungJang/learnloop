ALTER TABLE generation_runs
    ADD COLUMN source_link_ids_json TEXT NOT NULL DEFAULT '[]';
