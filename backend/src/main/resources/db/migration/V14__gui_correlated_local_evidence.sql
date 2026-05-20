ALTER TABLE source_bundles
    DROP CONSTRAINT source_bundles_auto_attribution_check,
    DROP CONSTRAINT source_bundles_status_check;

ALTER TABLE source_bundles
    ADD CONSTRAINT source_bundles_auto_attribution_check CHECK (
        auto_attribution IN ('ai_assisted', 'manual_or_unknown', 'gui_correlated')
    ),
    ADD CONSTRAINT source_bundles_status_check CHECK (
        status IN (
            'ready',
            'blocked_sensitive',
            'received',
            'rejected_by_path_or_size',
            'scanned_clean',
            'quarantined_secret',
            'redacted_stored',
            'generation_eligible',
            'user_confirmation_required',
            'purged_raw',
            'deleted'
        )
    );

ALTER TABLE source_bundle_attribution_events
    DROP CONSTRAINT source_bundle_attribution_events_auto_check;

ALTER TABLE source_bundle_attribution_events
    ADD CONSTRAINT source_bundle_attribution_events_auto_check CHECK (
        auto_attribution IS NULL OR auto_attribution IN ('ai_assisted', 'manual_or_unknown', 'gui_correlated')
    );
