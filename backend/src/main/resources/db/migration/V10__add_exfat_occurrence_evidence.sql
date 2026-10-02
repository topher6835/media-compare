ALTER TABLE file_entry ADD COLUMN occurrence_token TEXT COLLATE BINARY
    CHECK (occurrence_token IS NULL OR (
        typeof(occurrence_token) = 'text'
        AND length(occurrence_token) = 36
        AND length(CAST(occurrence_token AS BLOB)) = 36
        AND substr(occurrence_token, 9, 1) = '-'
        AND substr(occurrence_token, 14, 1) = '-'
        AND substr(occurrence_token, 19, 1) = '-'
        AND substr(occurrence_token, 24, 1) = '-'
        AND length(replace(occurrence_token, '-', '')) = 32
        AND replace(occurrence_token, '-', '') NOT GLOB '*[^0-9a-f]*'
    ));
ALTER TABLE file_entry ADD COLUMN observation_evidence_json TEXT
    CHECK ((occurrence_token IS NULL AND observation_evidence_json IS NULL)
        OR (occurrence_token IS NOT NULL AND observation_evidence_json IS NOT NULL
            AND typeof(observation_evidence_json) = 'text'
            AND length(CAST(observation_evidence_json AS BLOB)) BETWEEN 1 AND 1048576
            AND location_identity_status = 'RESOLVED'));
DROP INDEX uq_file_entry_resolved_location;
CREATE UNIQUE INDEX uq_file_entry_resolved_location
    ON file_entry(location_context_id, location_key)
    WHERE location_identity_status = 'RESOLVED' AND occurrence_token IS NULL;
CREATE UNIQUE INDEX uq_file_entry_occurrence_token ON file_entry(occurrence_token)
    WHERE occurrence_token IS NOT NULL;
CREATE INDEX idx_file_entry_exfat_location
    ON file_entry(location_context_id, location_key, id)
    WHERE location_identity_status = 'RESOLVED' AND occurrence_token IS NOT NULL;
