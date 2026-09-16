ALTER TABLE scan_run ADD COLUMN request_key TEXT;

ALTER TABLE job ADD COLUMN execution_version INTEGER NOT NULL DEFAULT 1
    CHECK (execution_version > 0);

ALTER TABLE job_stage ADD COLUMN result_json TEXT;

CREATE UNIQUE INDEX uq_scan_run_request_key
    ON scan_run (request_key)
    WHERE request_key IS NOT NULL;

CREATE UNIQUE INDEX uq_job_v2_scan_run
    ON job (scan_run_id)
    WHERE job_type = 'SCAN' AND execution_version = 2;

CREATE UNIQUE INDEX uq_job_active_v2_scan
    ON job (execution_version)
    WHERE job_type = 'SCAN'
      AND execution_version = 2
      AND status IN ('PENDING', 'RUNNING');
