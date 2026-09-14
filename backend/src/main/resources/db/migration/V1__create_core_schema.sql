CREATE TABLE source (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    root_path TEXT NOT NULL,
    root_path_key TEXT NOT NULL,
    location_revision INTEGER NOT NULL DEFAULT 0 CHECK (location_revision >= 0),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE content_record (
    id INTEGER PRIMARY KEY,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE working_set (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE scan_run (
    id INTEGER PRIMARY KEY,
    request_type TEXT NOT NULL,
    status TEXT NOT NULL,
    working_set_id INTEGER,
    options_version INTEGER NOT NULL CHECK (options_version > 0),
    options_json TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    started_at_ms INTEGER,
    finished_at_ms INTEGER,
    error_message TEXT,
    FOREIGN KEY (working_set_id) REFERENCES working_set (id) ON DELETE RESTRICT
);

CREATE TABLE scan_run_source (
    id INTEGER PRIMARY KEY,
    scan_run_id INTEGER NOT NULL,
    source_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    source_location_revision INTEGER NOT NULL CHECK (source_location_revision >= 0),
    traversal_generation INTEGER NOT NULL DEFAULT 0 CHECK (traversal_generation >= 0),
    completed_generation INTEGER,
    started_at_ms INTEGER,
    completed_at_ms INTEGER,
    error_message TEXT,
    CHECK (
        completed_generation IS NULL
        OR (completed_generation > 0 AND completed_generation <= traversal_generation)
    ),
    UNIQUE (scan_run_id, source_id),
    FOREIGN KEY (scan_run_id) REFERENCES scan_run (id) ON DELETE RESTRICT,
    FOREIGN KEY (source_id) REFERENCES source (id) ON DELETE RESTRICT
);

CREATE TABLE file_entry (
    id INTEGER PRIMARY KEY,
    source_id INTEGER NOT NULL,
    relative_path TEXT NOT NULL,
    path_key TEXT NOT NULL,
    current_content_id INTEGER,
    presence_status TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    modified_time_epoch_second INTEGER,
    modified_time_nano INTEGER CHECK (modified_time_nano BETWEEN 0 AND 999999999),
    observation_revision INTEGER NOT NULL DEFAULT 0 CHECK (observation_revision >= 0),
    first_seen_at_ms INTEGER NOT NULL,
    last_seen_at_ms INTEGER NOT NULL,
    last_seen_scan_run_source_id INTEGER,
    last_seen_traversal_generation INTEGER CHECK (last_seen_traversal_generation > 0),
    CHECK (
        (modified_time_epoch_second IS NULL AND modified_time_nano IS NULL)
        OR (modified_time_epoch_second IS NOT NULL AND modified_time_nano IS NOT NULL)
    ),
    UNIQUE (source_id, path_key),
    FOREIGN KEY (source_id) REFERENCES source (id) ON DELETE RESTRICT,
    FOREIGN KEY (current_content_id) REFERENCES content_record (id) ON DELETE RESTRICT,
    FOREIGN KEY (last_seen_scan_run_source_id) REFERENCES scan_run_source (id) ON DELETE SET NULL
);

CREATE TABLE working_set_content (
    working_set_id INTEGER NOT NULL,
    content_record_id INTEGER NOT NULL,
    added_at_ms INTEGER NOT NULL,
    PRIMARY KEY (working_set_id, content_record_id),
    FOREIGN KEY (working_set_id) REFERENCES working_set (id) ON DELETE CASCADE,
    FOREIGN KEY (content_record_id) REFERENCES content_record (id) ON DELETE RESTRICT
);

CREATE TABLE job (
    id INTEGER PRIMARY KEY,
    scan_run_id INTEGER,
    job_type TEXT NOT NULL,
    status TEXT NOT NULL,
    current_stage_type TEXT,
    progress_completed INTEGER NOT NULL DEFAULT 0 CHECK (progress_completed >= 0),
    progress_total INTEGER CHECK (progress_total >= 0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at_ms INTEGER NOT NULL,
    started_at_ms INTEGER,
    finished_at_ms INTEGER,
    error_message TEXT,
    FOREIGN KEY (scan_run_id) REFERENCES scan_run (id) ON DELETE RESTRICT
);

CREATE TABLE job_stage (
    id INTEGER PRIMARY KEY,
    job_id INTEGER NOT NULL,
    stage_type TEXT NOT NULL,
    status TEXT NOT NULL,
    progress_completed INTEGER NOT NULL DEFAULT 0 CHECK (progress_completed >= 0),
    progress_total INTEGER CHECK (progress_total >= 0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at_ms INTEGER NOT NULL,
    started_at_ms INTEGER,
    finished_at_ms INTEGER,
    error_message TEXT,
    UNIQUE (job_id, stage_type),
    FOREIGN KEY (job_id) REFERENCES job (id) ON DELETE CASCADE
);

CREATE TABLE analysis_record (
    id INTEGER PRIMARY KEY,
    content_record_id INTEGER NOT NULL,
    analysis_type TEXT NOT NULL,
    analyzer_id TEXT NOT NULL,
    analyzer_version TEXT NOT NULL,
    configuration_version INTEGER NOT NULL CHECK (configuration_version > 0),
    configuration_hash TEXT NOT NULL,
    configuration_json TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at_ms INTEGER NOT NULL,
    started_at_ms INTEGER,
    finished_at_ms INTEGER,
    error_message TEXT,
    UNIQUE (
        content_record_id,
        analysis_type,
        analyzer_id,
        analyzer_version,
        configuration_version,
        configuration_hash
    ),
    FOREIGN KEY (content_record_id) REFERENCES content_record (id) ON DELETE RESTRICT
);

CREATE TABLE content_hash (
    analysis_record_id INTEGER PRIMARY KEY,
    algorithm TEXT NOT NULL,
    digest_hex TEXT NOT NULL,
    FOREIGN KEY (analysis_record_id) REFERENCES analysis_record (id) ON DELETE CASCADE
);

CREATE INDEX idx_file_entry_content_presence
    ON file_entry (current_content_id, presence_status);

CREATE INDEX idx_file_entry_source_reconciliation
    ON file_entry (source_id, presence_status, last_seen_traversal_generation);

CREATE INDEX idx_working_set_content_content
    ON working_set_content (content_record_id);

CREATE INDEX idx_scan_run_source_source_status
    ON scan_run_source (source_id, status);

CREATE INDEX idx_job_scan_run
    ON job (scan_run_id);

CREATE INDEX idx_content_hash_digest
    ON content_hash (algorithm, digest_hex);
