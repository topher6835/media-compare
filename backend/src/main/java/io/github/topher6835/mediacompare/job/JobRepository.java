package io.github.topher6835.mediacompare.job;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private final JdbcTemplate jdbcTemplate;

    public JobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Job insert(Job job) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO job (
                        scan_run_id, job_type, execution_version, status, current_stage_type, progress_completed,
                        progress_total, attempt_count, created_at_ms, started_at_ms, finished_at_ms,
                        error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            setNullableLong(statement, 1, job.scanRunId());
            statement.setString(2, job.jobType());
            statement.setLong(3, job.executionVersion());
            statement.setString(4, job.status());
            statement.setString(5, job.currentStageType());
            statement.setLong(6, job.progressCompleted());
            setNullableLong(statement, 7, job.progressTotal());
            statement.setLong(8, job.attemptCount());
            statement.setLong(9, job.createdAtMs());
            setNullableLong(statement, 10, job.startedAtMs());
            setNullableLong(statement, 11, job.finishedAtMs());
            statement.setString(12, job.errorMessage());
            return statement;
        }, keyHolder);

        return new Job(generatedId(keyHolder), job.scanRunId(), job.jobType(), job.executionVersion(), job.status(),
                job.currentStageType(), job.progressCompleted(), job.progressTotal(), job.attemptCount(),
                job.createdAtMs(), job.startedAtMs(), job.finishedAtMs(), job.errorMessage());
    }

    public Optional<Job> findJobById(long id) {
        return jdbcTemplate.query("SELECT * FROM job WHERE id = ?", JobRepository::mapJob, id)
                .stream()
                .findFirst();
    }

    public Optional<Job> findJobByScanRunIdAndTypeAndExecutionVersion(
            long scanRunId, String jobType, long executionVersion) {
        return jdbcTemplate.query("""
                SELECT * FROM job
                WHERE scan_run_id = ? AND job_type = ? AND execution_version = ?
                """, JobRepository::mapJob, scanRunId, jobType, executionVersion)
                .stream()
                .findFirst();
    }

    public JobStage insert(JobStage jobStage) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO job_stage (
                        job_id, stage_type, result_json, status, progress_completed, progress_total, attempt_count,
                        created_at_ms, started_at_ms, finished_at_ms, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, jobStage.jobId());
            statement.setString(2, jobStage.stageType());
            statement.setString(3, jobStage.resultJson());
            statement.setString(4, jobStage.status());
            statement.setLong(5, jobStage.progressCompleted());
            setNullableLong(statement, 6, jobStage.progressTotal());
            statement.setLong(7, jobStage.attemptCount());
            statement.setLong(8, jobStage.createdAtMs());
            setNullableLong(statement, 9, jobStage.startedAtMs());
            setNullableLong(statement, 10, jobStage.finishedAtMs());
            statement.setString(11, jobStage.errorMessage());
            return statement;
        }, keyHolder);

        return new JobStage(generatedId(keyHolder), jobStage.jobId(), jobStage.stageType(), jobStage.resultJson(),
                jobStage.status(), jobStage.progressCompleted(), jobStage.progressTotal(), jobStage.attemptCount(),
                jobStage.createdAtMs(), jobStage.startedAtMs(), jobStage.finishedAtMs(), jobStage.errorMessage());
    }

    public Optional<JobStage> findJobStageById(long id) {
        return jdbcTemplate.query("SELECT * FROM job_stage WHERE id = ?", JobRepository::mapJobStage, id)
                .stream()
                .findFirst();
    }

    public List<JobStage> findJobStagesByJobId(long jobId) {
        return jdbcTemplate.query("""
                SELECT * FROM job_stage
                WHERE job_id = ?
                ORDER BY id
                """, JobRepository::mapJobStage, jobId);
    }

    public Optional<JobStage> findJobStageByJobIdAndType(long jobId, String stageType) {
        return jdbcTemplate.query("""
                SELECT * FROM job_stage
                WHERE job_id = ? AND stage_type = ?
                """, JobRepository::mapJobStage, jobId, stageType).stream().findFirst();
    }

    public int startJob(long jobId, long executionVersion, long startedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job
                SET status = 'RUNNING', attempt_count = attempt_count + 1, started_at_ms = ?,
                    finished_at_ms = NULL, error_message = NULL
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = ? AND status = 'PENDING'
                  AND current_stage_type = 'DISCOVERY'
                """, startedAtMs, jobId, executionVersion);
    }

    public int startDiscoveryStage(long jobStageId, long executionVersion, long startedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job_stage
                SET status = 'RUNNING', progress_completed = 0, progress_total = NULL,
                    attempt_count = attempt_count + 1, started_at_ms = ?, finished_at_ms = NULL,
                    error_message = NULL
                WHERE id = ? AND stage_type = 'DISCOVERY' AND status = 'PENDING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id AND job.execution_version = ?
                  )
                """, startedAtMs, jobStageId, executionVersion);
    }

    public int updateDiscoveryProgress(long jobId, long jobStageId, long executionVersion,
            long progressCompleted) {
        int stageRows = jdbcTemplate.update("""
                UPDATE job_stage SET progress_completed = ?
                WHERE id = ? AND stage_type = 'DISCOVERY' AND status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id AND job.execution_version = ?
                  )
                """, progressCompleted, jobStageId, executionVersion);
        int jobRows = jdbcTemplate.update("""
                UPDATE job SET progress_completed = ?
                WHERE id = ? AND execution_version = ? AND status = 'RUNNING'
                  AND current_stage_type = 'DISCOVERY'
                """, progressCompleted, jobId, executionVersion);
        return stageRows + jobRows;
    }

    public int completeDiscoveryStage(long jobStageId, long executionVersion, long finalCount,
            long finishedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job_stage
                SET status = 'COMPLETED', progress_completed = ?, progress_total = ?,
                    finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND stage_type = 'DISCOVERY' AND status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id AND job.execution_version = ?
                  )
                """, finalCount, finalCount, finishedAtMs, jobStageId, executionVersion);
    }

    public int advanceJobToReconciliation(long jobId, long executionVersion, long finalCount) {
        return jdbcTemplate.update("""
                UPDATE job
                SET current_stage_type = 'RECONCILIATION', progress_completed = ?, progress_total = ?,
                    finished_at_ms = NULL, error_message = NULL
                WHERE id = ? AND execution_version = ? AND status = 'RUNNING'
                  AND current_stage_type = 'DISCOVERY'
                """, finalCount, finalCount, jobId, executionVersion);
    }

    public int failDiscoveryStage(long jobStageId, long executionVersion, long failedAtMs,
            String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE job_stage
                SET status = 'FAILED', finished_at_ms = ?, error_message = ?
                WHERE id = ? AND stage_type = 'DISCOVERY' AND status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id AND job.execution_version = ?
                  )
                """, failedAtMs, errorMessage, jobStageId, executionVersion);
    }

    public int failDiscoveryJob(long jobId, long executionVersion, long failedAtMs, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE job
                SET status = 'FAILED', finished_at_ms = ?, error_message = ?
                WHERE id = ? AND execution_version = ? AND status = 'RUNNING'
                  AND current_stage_type = 'DISCOVERY'
                """, failedAtMs, errorMessage, jobId, executionVersion);
    }

    public int startReconciliation(long jobId, long jobStageId, long executionVersion,
            long sourceCount, long startedAtMs) {
        int stageRows = jdbcTemplate.update("""
                UPDATE job_stage
                SET status = 'RUNNING', progress_completed = 0, progress_total = ?,
                    attempt_count = attempt_count + 1, started_at_ms = ?, finished_at_ms = NULL,
                    error_message = NULL
                WHERE id = ? AND stage_type = 'RECONCILIATION' AND status = 'PENDING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id AND job.execution_version = ?
                  )
                """, sourceCount, startedAtMs, jobStageId, executionVersion);
        int jobRows = jdbcTemplate.update("""
                UPDATE job
                SET progress_completed = 0, progress_total = ?, finished_at_ms = NULL,
                    error_message = NULL
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = ? AND status = 'RUNNING'
                  AND current_stage_type = 'RECONCILIATION'
                """, sourceCount, jobId, executionVersion);
        return stageRows + jobRows;
    }

    public int updateReconciliationProgress(long jobId, long jobStageId, long executionVersion,
            long progressCompleted) {
        int stageRows = jdbcTemplate.update("""
                UPDATE job_stage SET progress_completed = ?
                WHERE id = ? AND stage_type = 'RECONCILIATION' AND status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id AND job.execution_version = ?
                  )
                """, progressCompleted, jobStageId, executionVersion);
        int jobRows = jdbcTemplate.update("""
                UPDATE job SET progress_completed = ?
                WHERE id = ? AND execution_version = ? AND status = 'RUNNING'
                  AND current_stage_type = 'RECONCILIATION'
                """, progressCompleted, jobId, executionVersion);
        return stageRows + jobRows;
    }

    public int completeReconciliationStage(long jobStageId, long executionVersion,
            long sourceCount, long finishedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job_stage
                SET status = 'COMPLETED', progress_completed = ?, progress_total = ?,
                    finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND stage_type = 'RECONCILIATION' AND status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id AND job.execution_version = ?
                  )
                """, sourceCount, sourceCount, finishedAtMs, jobStageId, executionVersion);
    }

    public int completeVersion1Job(long jobId, long sourceCount, long finishedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job
                SET status = 'COMPLETED', current_stage_type = NULL,
                    progress_completed = ?, progress_total = ?, finished_at_ms = ?,
                    error_message = NULL
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = 1 AND status = 'RUNNING'
                  AND current_stage_type = 'RECONCILIATION'
                """, sourceCount, sourceCount, finishedAtMs, jobId);
    }

    public int claimVersion2Stage(long jobId, long jobStageId, String stageType, long startedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job_stage
                SET status = 'RUNNING', progress_completed = 0, progress_total = NULL,
                    attempt_count = attempt_count + 1, started_at_ms = ?, finished_at_ms = NULL,
                    error_message = NULL
                WHERE id = ? AND stage_type = ? AND status = 'PENDING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id
                        AND job.id = ?
                        AND job.job_type = 'SCAN'
                        AND job.execution_version = 2
                        AND job.status = 'RUNNING'
                        AND job.current_stage_type = ?
                  )
                """, startedAtMs, jobStageId, stageType, jobId, stageType);
    }

    public int resetVersion2JobProgress(long jobId, String stageType) {
        return jdbcTemplate.update("""
                UPDATE job
                SET progress_completed = 0, progress_total = NULL,
                    finished_at_ms = NULL, error_message = NULL
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = 2
                  AND status = 'RUNNING' AND current_stage_type = ?
                """, jobId, stageType);
    }

    public int completeVersion2Stage(long jobId, long jobStageId, String stageType,
            String resultJson, long progressCompleted, long finishedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job_stage
                SET result_json = ?, status = 'COMPLETED', progress_completed = ?, progress_total = ?,
                    finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND job_id = ? AND stage_type = ? AND status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id
                        AND job.job_type = 'SCAN'
                        AND job.execution_version = 2
                        AND job.status = 'RUNNING'
                        AND job.current_stage_type = ?
                  )
                """, resultJson, progressCompleted, progressCompleted, finishedAtMs,
                jobStageId, jobId, stageType, stageType);
    }

    public int advanceVersion2Job(long jobId, String currentStageType, String nextStageType,
            long progressCompleted) {
        return jdbcTemplate.update("""
                UPDATE job
                SET current_stage_type = ?, progress_completed = ?, progress_total = ?,
                    finished_at_ms = NULL, error_message = NULL
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = 2
                  AND status = 'RUNNING' AND current_stage_type = ?
                """, nextStageType, progressCompleted, progressCompleted, jobId, currentStageType);
    }

    public int completeVersion2Job(long jobId, long progressCompleted, long finishedAtMs) {
        return jdbcTemplate.update("""
                UPDATE job
                SET status = 'COMPLETED', current_stage_type = NULL,
                    progress_completed = ?, progress_total = ?, finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = 2
                  AND status = 'RUNNING' AND current_stage_type = 'CONTENT_HASHING'
                """, progressCompleted, progressCompleted, finishedAtMs, jobId);
    }

    public int failVersion2Stage(long jobId, long jobStageId, String stageType,
            long failedAtMs, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE job_stage
                SET status = 'FAILED', finished_at_ms = ?, error_message = ?
                WHERE id = ? AND job_id = ? AND stage_type = ? AND status = 'RUNNING'
                  AND EXISTS (
                      SELECT 1 FROM job
                      WHERE job.id = job_stage.job_id
                        AND job.execution_version = 2
                        AND job.status = 'RUNNING'
                        AND job.current_stage_type = ?
                  )
                """, failedAtMs, errorMessage, jobStageId, jobId, stageType, stageType);
    }

    public int failVersion2Job(long jobId, String stageType, long failedAtMs, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE job
                SET status = 'FAILED', current_stage_type = NULL, finished_at_ms = ?, error_message = ?
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = 2
                  AND status = 'RUNNING' AND current_stage_type = ?
                """, failedAtMs, errorMessage, jobId, stageType);
    }

    public List<Job> findActiveVersion2ScanJobs() {
        return jdbcTemplate.query("""
                SELECT * FROM job WHERE job_type = 'SCAN' AND execution_version = 2
                  AND status IN ('PENDING', 'RUNNING') ORDER BY id
                """, JobRepository::mapJob);
    }

    public int failActiveVersion2Job(long jobId, long failedAtMs, String message) {
        return jdbcTemplate.update("""
                UPDATE job SET status = 'FAILED', current_stage_type = NULL,
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND job_type = 'SCAN' AND execution_version = 2
                  AND status IN ('PENDING', 'RUNNING')
                """, failedAtMs, message, jobId);
    }

    private static long generatedId(GeneratedKeyHolder keyHolder) {
        return Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
    }

    private static Job mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Job(
                resultSet.getLong("id"),
                nullableLong(resultSet, "scan_run_id"),
                resultSet.getString("job_type"),
                resultSet.getLong("execution_version"),
                resultSet.getString("status"),
                resultSet.getString("current_stage_type"),
                resultSet.getLong("progress_completed"),
                nullableLong(resultSet, "progress_total"),
                resultSet.getLong("attempt_count"),
                resultSet.getLong("created_at_ms"),
                nullableLong(resultSet, "started_at_ms"),
                nullableLong(resultSet, "finished_at_ms"),
                resultSet.getString("error_message"));
    }

    private static JobStage mapJobStage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobStage(
                resultSet.getLong("id"),
                resultSet.getLong("job_id"),
                resultSet.getString("stage_type"),
                resultSet.getString("result_json"),
                resultSet.getString("status"),
                resultSet.getLong("progress_completed"),
                nullableLong(resultSet, "progress_total"),
                resultSet.getLong("attempt_count"),
                resultSet.getLong("created_at_ms"),
                nullableLong(resultSet, "started_at_ms"),
                nullableLong(resultSet, "finished_at_ms"),
                resultSet.getString("error_message"));
    }

    private static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }
}
