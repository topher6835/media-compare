CREATE TABLE location_context (
    id TEXT COLLATE BINARY NOT NULL PRIMARY KEY,
    anchor_location_path TEXT NOT NULL,
    anchor_location_key TEXT COLLATE BINARY NOT NULL,
    lifecycle_status TEXT NOT NULL,
    continuity_status TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    continuity_evidence_json TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    CHECK (
        continuity_status <> 'ACCEPTED'
        OR continuity_evidence_json IS NOT NULL
    )
);

CREATE UNIQUE INDEX uq_location_context_active_anchor
    ON location_context (anchor_location_key)
    WHERE lifecycle_status = 'ACTIVE';

ALTER TABLE source ADD COLUMN root_path_dialect TEXT;

ALTER TABLE source ADD COLUMN bound_location_context_id TEXT
    REFERENCES location_context (id) ON DELETE RESTRICT;

ALTER TABLE source ADD COLUMN binding_evidence_json TEXT;

CREATE INDEX idx_source_bound_location_context
    ON source (bound_location_context_id);
