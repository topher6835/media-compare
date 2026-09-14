package io.github.topher6835.mediacompare.job;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
                        scan_run_id, job_type, status, current_stage_type, progress_completed,
                        progress_total, attempt_count, created_at_ms, started_at_ms, finished_at_ms,
                        error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            setNullableLong(statement, 1, job.scanRunId());
            statement.setString(2, job.jobType());
            statement.setString(3, job.status());
            statement.setString(4, job.currentStageType());
            statement.setLong(5, job.progressCompleted());
            setNullableLong(statement, 6, job.progressTotal());
            statement.setLong(7, job.attemptCount());
            statement.setLong(8, job.createdAtMs());
            setNullableLong(statement, 9, job.startedAtMs());
            setNullableLong(statement, 10, job.finishedAtMs());
            statement.setString(11, job.errorMessage());
            return statement;
        }, keyHolder);

        return new Job(generatedId(keyHolder), job.scanRunId(), job.jobType(), job.status(), job.currentStageType(),
                job.progressCompleted(), job.progressTotal(), job.attemptCount(), job.createdAtMs(), job.startedAtMs(),
                job.finishedAtMs(), job.errorMessage());
    }

    public Optional<Job> findJobById(long id) {
        return jdbcTemplate.query("SELECT * FROM job WHERE id = ?", (resultSet, rowNumber) -> new Job(
                resultSet.getLong("id"),
                nullableLong(resultSet, "scan_run_id"),
                resultSet.getString("job_type"),
                resultSet.getString("status"),
                resultSet.getString("current_stage_type"),
                resultSet.getLong("progress_completed"),
                nullableLong(resultSet, "progress_total"),
                resultSet.getLong("attempt_count"),
                resultSet.getLong("created_at_ms"),
                nullableLong(resultSet, "started_at_ms"),
                nullableLong(resultSet, "finished_at_ms"),
                resultSet.getString("error_message")), id).stream().findFirst();
    }

    public JobStage insert(JobStage jobStage) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO job_stage (
                        job_id, stage_type, status, progress_completed, progress_total, attempt_count,
                        created_at_ms, started_at_ms, finished_at_ms, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, jobStage.jobId());
            statement.setString(2, jobStage.stageType());
            statement.setString(3, jobStage.status());
            statement.setLong(4, jobStage.progressCompleted());
            setNullableLong(statement, 5, jobStage.progressTotal());
            statement.setLong(6, jobStage.attemptCount());
            statement.setLong(7, jobStage.createdAtMs());
            setNullableLong(statement, 8, jobStage.startedAtMs());
            setNullableLong(statement, 9, jobStage.finishedAtMs());
            statement.setString(10, jobStage.errorMessage());
            return statement;
        }, keyHolder);

        return new JobStage(generatedId(keyHolder), jobStage.jobId(), jobStage.stageType(), jobStage.status(),
                jobStage.progressCompleted(), jobStage.progressTotal(), jobStage.attemptCount(), jobStage.createdAtMs(),
                jobStage.startedAtMs(), jobStage.finishedAtMs(), jobStage.errorMessage());
    }

    public Optional<JobStage> findJobStageById(long id) {
        return jdbcTemplate.query("SELECT * FROM job_stage WHERE id = ?", (resultSet, rowNumber) -> new JobStage(
                resultSet.getLong("id"),
                resultSet.getLong("job_id"),
                resultSet.getString("stage_type"),
                resultSet.getString("status"),
                resultSet.getLong("progress_completed"),
                nullableLong(resultSet, "progress_total"),
                resultSet.getLong("attempt_count"),
                resultSet.getLong("created_at_ms"),
                nullableLong(resultSet, "started_at_ms"),
                nullableLong(resultSet, "finished_at_ms"),
                resultSet.getString("error_message")), id).stream().findFirst();
    }

    private static long generatedId(GeneratedKeyHolder keyHolder) {
        return Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
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
