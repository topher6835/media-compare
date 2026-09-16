package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import io.github.topher6835.mediacompare.analysis.Version2ContentHashingService;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Version2ScanExecutionService {

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final ScanExecutionService discoveryService;
    private final ReconciliationService reconciliationService;
    private final Version2ContentAssignmentService assignmentService;
    private final Version2ContentHashingService hashingService;

    public Version2ScanExecutionService(ScanRepository scanRepository, JobRepository jobRepository,
            ScanExecutionService discoveryService, ReconciliationService reconciliationService,
            Version2ContentAssignmentService assignmentService,
            Version2ContentHashingService hashingService) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.discoveryService = discoveryService;
        this.reconciliationService = reconciliationService;
        this.assignmentService = assignmentService;
        this.hashingService = hashingService;
    }

    @Transactional
    public ScanExecutionDetails create(long scanRunId) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        if (!"INDEX".equals(scanRun.requestType()) || !"PENDING".equals(scanRun.status())) {
            throw new Version2ExecutionConflictException(
                    "ScanRun is not eligible for a version-2 execution");
        }
        if (jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, ScanExecutionDefinition.VERSION_2).isPresent()) {
            throw new Version2ExecutionConflictException(
                    "Version-2 execution already exists for ScanRun " + scanRunId);
        }

        long createdAtMs = System.currentTimeMillis();
        try {
            Job job = jobRepository.insert(new Job(
                    null, scanRunId, ScanExecutionDefinition.JOB_TYPE, ScanExecutionDefinition.VERSION_2,
                    "PENDING", ScanExecutionDefinition.DISCOVERY, 0, null, 0,
                    createdAtMs, null, null, null));
            JobStage discovery = jobRepository.insert(new JobStage(
                    null, job.id(), ScanExecutionDefinition.DISCOVERY, null,
                    "PENDING", 0, null, 0, createdAtMs, null, null, null));
            return new ScanExecutionDetails(job, List.of(discovery));
        } catch (DataAccessException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            throw new Version2ExecutionConflictException(
                    "Another active version-2 SCAN execution prevents creation", exception);
        }
    }

    public Optional<ScanExecutionDetails> findByScanRunId(long scanRunId) {
        return discoveryService.findByScanRunIdAndVersion(scanRunId, ScanExecutionDefinition.VERSION_2);
    }

    public ScanExecutionDetails run(long scanRunId) {
        discoveryService.executeVersion2Discovery(scanRunId);
        reconciliationService.executeVersion2(scanRunId);
        assignmentService.execute(scanRunId);
        hashingService.execute(scanRunId);
        return findByScanRunId(scanRunId).orElseThrow();
    }

    private static boolean isConstraintViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.sql.SQLException sqlException
                    && sqlException.getErrorCode() == 19) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
