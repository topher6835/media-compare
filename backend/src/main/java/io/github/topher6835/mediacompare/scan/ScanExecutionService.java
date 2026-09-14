package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanExecutionService {

    private static final String SCAN_JOB_TYPE = "SCAN";
    private static final String PENDING_STATUS = "PENDING";
    private static final String DISCOVERY_STAGE_TYPE = "DISCOVERY";

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;

    public ScanExecutionService(ScanRepository scanRepository, JobRepository jobRepository) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public ScanExecutionDetails create(long scanRunId) {
        requireScanRun(scanRunId);
        if (jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE).isPresent()) {
            throw new ScanExecutionAlreadyExistsException(scanRunId);
        }

        long createdAtMs = System.currentTimeMillis();
        Job job = jobRepository.insert(new Job(
                null,
                scanRunId,
                SCAN_JOB_TYPE,
                PENDING_STATUS,
                DISCOVERY_STAGE_TYPE,
                0,
                null,
                0,
                createdAtMs,
                null,
                null,
                null));

        JobStage discoveryStage = jobRepository.insert(new JobStage(
                null,
                job.id(),
                DISCOVERY_STAGE_TYPE,
                PENDING_STATUS,
                0,
                null,
                0,
                createdAtMs,
                null,
                null,
                null));

        return new ScanExecutionDetails(job, List.of(discoveryStage));
    }

    public Optional<ScanExecutionDetails> findByScanRunId(long scanRunId) {
        if (scanRepository.findScanRunById(scanRunId).isEmpty()) {
            return Optional.empty();
        }

        return jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE)
                .map(job -> new ScanExecutionDetails(job, jobRepository.findJobStagesByJobId(job.id())));
    }

    private void requireScanRun(long scanRunId) {
        if (scanRepository.findScanRunById(scanRunId).isEmpty()) {
            throw new NoSuchElementException("ScanRun " + scanRunId + " does not exist");
        }
    }
}
