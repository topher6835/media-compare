package io.github.topher6835.mediacompare.scan;

import java.util.List;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DiscoveryExecutionState {

    private static final String PENDING_STATUS = "PENDING";

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;

    public DiscoveryExecutionState(ScanRepository scanRepository, JobRepository jobRepository) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void start(long scanRunId, Job job, JobStage discoveryStage, List<DiscoverySource> sources,
            long startedAtMs) {
        requireOne(scanRepository.startScanRun(scanRunId, startedAtMs), "start ScanRun");
        requireOne(jobRepository.startJob(job.id(), job.executionVersion(), startedAtMs), "start Job");
        requireOne(jobRepository.startDiscoveryStage(
                discoveryStage.id(), job.executionVersion(), startedAtMs), "start DISCOVERY stage");
        for (DiscoverySource source : sources) {
            requireOne(scanRepository.startSourceDiscovery(
                    source.scanRunSource().id(), source.traversalGeneration(), startedAtMs),
                    "start Source discovery");
        }
    }

    @Transactional
    public void complete(Job job, JobStage discoveryStage, List<DiscoverySource> sources,
            long finalCount, long completedAtMs) {
        for (DiscoverySource source : sources) {
            requireOne(scanRepository.completeSourceDiscovery(source.scanRunSource().id()),
                    "complete Source discovery");
        }
        requireOne(jobRepository.completeDiscoveryStage(
                discoveryStage.id(), job.executionVersion(), finalCount, completedAtMs),
                "complete DISCOVERY stage");
        jobRepository.insert(new JobStage(
                null,
                job.id(),
                ScanExecutionDefinition.RECONCILIATION,
                null,
                PENDING_STATUS,
                0,
                null,
                0,
                completedAtMs,
                null,
                null,
                null));
        requireOne(jobRepository.advanceJobToReconciliation(
                job.id(), job.executionVersion(), finalCount),
                "advance Job to RECONCILIATION");
    }

    @Transactional
    public void fail(long scanRunId, Job job, JobStage discoveryStage, List<DiscoverySource> sources,
            DiscoverySource affectedSource, long failedAtMs, String errorMessage) {
        for (DiscoverySource source : sources) {
            requireOne(scanRepository.failSourceDiscovery(
                    source.scanRunSource().id(), failedAtMs, errorMessage),
                    "fail Source discovery after Source " + affectedSource.source().id() + " failed");
        }
        requireOne(jobRepository.failDiscoveryStage(
                discoveryStage.id(), job.executionVersion(), failedAtMs, errorMessage),
                "fail DISCOVERY stage");
        if (job.executionVersion() == ScanExecutionDefinition.VERSION_2) {
            requireOne(jobRepository.failVersion2Job(
                    job.id(), ScanExecutionDefinition.DISCOVERY, failedAtMs, errorMessage), "fail Job");
        } else {
            requireOne(jobRepository.failDiscoveryJob(
                    job.id(), job.executionVersion(), failedAtMs, errorMessage), "fail Job");
        }
        requireOne(scanRepository.failScanRun(scanRunId, failedAtMs, errorMessage), "fail ScanRun");
    }

    private static void requireOne(int rows, String action) {
        if (rows != 1) {
            throw new IllegalStateException("Could not " + action);
        }
    }
}
