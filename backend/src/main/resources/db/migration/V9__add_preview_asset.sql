CREATE TABLE preview_asset (
    id INTEGER PRIMARY KEY CHECK (id > 0),
    asset_key TEXT NOT NULL UNIQUE CHECK (
        length(asset_key) = 64 AND length(CAST(asset_key AS BLOB)) = 64
        AND asset_key NOT GLOB '*[^0-9a-f]*'
    ),
    file_entry_id INTEGER NOT NULL CHECK (file_entry_id > 0),
    content_record_id INTEGER NOT NULL CHECK (content_record_id > 0),
    file_observation_revision INTEGER NOT NULL CHECK (
        typeof(file_observation_revision) = 'integer' AND file_observation_revision >= 0
    ),
    source_size_bytes INTEGER NOT NULL CHECK (typeof(source_size_bytes) = 'integer' AND source_size_bytes >= 0),
    source_modified_time_epoch_second INTEGER NOT NULL CHECK (typeof(source_modified_time_epoch_second) = 'integer'),
    source_modified_time_nano INTEGER NOT NULL CHECK (
        typeof(source_modified_time_nano) = 'integer' AND source_modified_time_nano BETWEEN 0 AND 999999999
    ),
    preview_kind TEXT NOT NULL CHECK (length(trim(preview_kind, ' ' || char(9,10,11,12,13))) > 0),
    generator_id TEXT NOT NULL CHECK (length(trim(generator_id, ' ' || char(9,10,11,12,13))) > 0),
    generator_version TEXT NOT NULL CHECK (length(trim(generator_version, ' ' || char(9,10,11,12,13))) > 0),
    configuration_version INTEGER NOT NULL CHECK (typeof(configuration_version) = 'integer' AND configuration_version > 0),
    configuration_hash TEXT NOT NULL CHECK (length(trim(configuration_hash, ' ' || char(9,10,11,12,13))) > 0),
    configuration_json TEXT NOT NULL CHECK (length(CAST(configuration_json AS BLOB)) BETWEEN 1 AND 131072)
        CHECK (CASE WHEN json_valid(configuration_json) THEN json_type(configuration_json) = 'object' ELSE 0 END),
    relative_path TEXT NOT NULL CHECK (
        length(relative_path) BETWEEN 1 AND 512
        AND length(CAST(relative_path AS BLOB)) = length(relative_path)
        AND relative_path NOT GLOB '*[^a-z0-9/._-]*'
        AND substr(relative_path, 1, 1) <> '/' AND substr(relative_path, -1) <> '/'
        AND instr(relative_path, '..') = 0 AND instr(relative_path, '//') = 0
        AND instr('/' || relative_path || '/', '/./') = 0
    ),
    media_type TEXT NOT NULL CHECK (length(trim(media_type, ' ' || char(9,10,11,12,13))) > 0),
    pixel_width INTEGER NOT NULL CHECK (typeof(pixel_width) = 'integer' AND pixel_width > 0),
    pixel_height INTEGER NOT NULL CHECK (typeof(pixel_height) = 'integer' AND pixel_height > 0),
    asset_size_bytes INTEGER NOT NULL CHECK (typeof(asset_size_bytes) = 'integer' AND asset_size_bytes > 0),
    created_at_ms INTEGER NOT NULL CHECK (typeof(created_at_ms) = 'integer' AND created_at_ms >= 0),
    UNIQUE (
        file_entry_id, preview_kind, content_record_id, file_observation_revision,
        source_size_bytes, source_modified_time_epoch_second, source_modified_time_nano,
        generator_id, generator_version, configuration_version, configuration_hash
    ),
    FOREIGN KEY (file_entry_id) REFERENCES file_entry (id) ON DELETE CASCADE,
    FOREIGN KEY (content_record_id) REFERENCES content_record (id) ON DELETE CASCADE
);

-- The two UNIQUE indexes already cover immutable-key and FileEntry/kind lookup.
CREATE INDEX idx_preview_asset_created_at ON preview_asset (created_at_ms, id);
