CREATE TABLE media_relationship (
    id INTEGER PRIMARY KEY,
    content_record_a_id INTEGER NOT NULL,
    content_record_b_id INTEGER NOT NULL,
    relationship_type TEXT NOT NULL CHECK (length(trim(relationship_type)) > 0),
    direction TEXT NOT NULL CHECK (direction IN ('UNDIRECTED', 'A_TO_B', 'B_TO_A')),
    confidence REAL CHECK (
        confidence IS NULL
        OR (typeof(confidence) IN ('integer', 'real') AND confidence BETWEEN 0.0 AND 1.0)
    ),
    evidence_json TEXT NOT NULL CHECK (length(CAST(evidence_json AS BLOB)) BETWEEN 1 AND 131072)
        CHECK (CASE WHEN json_valid(evidence_json) THEN json_type(evidence_json) = 'object' ELSE 0 END),
    matcher_id TEXT NOT NULL CHECK (length(trim(matcher_id)) > 0),
    matcher_version TEXT NOT NULL CHECK (length(trim(matcher_version)) > 0),
    configuration_version INTEGER NOT NULL CHECK (configuration_version > 0),
    configuration_hash TEXT NOT NULL CHECK (length(trim(configuration_hash)) > 0),
    configuration_json TEXT NOT NULL CHECK (length(CAST(configuration_json AS BLOB)) BETWEEN 1 AND 131072)
        CHECK (CASE WHEN json_valid(configuration_json) THEN json_type(configuration_json) = 'object' ELSE 0 END),
    created_at_ms INTEGER NOT NULL,
    CHECK (content_record_a_id < content_record_b_id),
    UNIQUE (
        content_record_a_id, content_record_b_id, relationship_type,
        matcher_id, matcher_version, configuration_version, configuration_hash
    ),
    FOREIGN KEY (content_record_a_id) REFERENCES content_record (id) ON DELETE RESTRICT,
    FOREIGN KEY (content_record_b_id) REFERENCES content_record (id) ON DELETE RESTRICT
);

CREATE INDEX idx_media_relationship_a_type
    ON media_relationship (content_record_a_id, relationship_type);

CREATE INDEX idx_media_relationship_b_type
    ON media_relationship (content_record_b_id, relationship_type);

CREATE INDEX idx_media_relationship_type_endpoints
    ON media_relationship (relationship_type, content_record_a_id, content_record_b_id);
