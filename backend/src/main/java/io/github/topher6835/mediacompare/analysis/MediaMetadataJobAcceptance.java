package io.github.topher6835.mediacompare.analysis;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MediaMetadataJobAcceptance {

    private final ReentrantLock admissionLock = new ReentrantLock();
    private final TransactionalAcceptance transactions;

    public MediaMetadataJobAcceptance(TransactionalAcceptance transactions) {
        this.transactions = transactions;
    }

    public MediaMetadataExecutionDetails create() {
        admissionLock.lock();
        try {
            return transactions.create();
        } finally {
            admissionLock.unlock();
        }
    }

    @Component
    public static class TransactionalAcceptance {

        private final JobRepository jobs;

        public TransactionalAcceptance(JobRepository jobs) {
            this.jobs = jobs;
        }

        @Transactional
        public MediaMetadataExecutionDetails create() {
            jobs.reserveAdmissionWrite();
            if (!jobs.findActiveMediaMetadataJobs().isEmpty()) {
                throw new MediaMetadataJobConflictException("A media metadata Job is already active");
            }

            long createdAtMs = System.currentTimeMillis();
            Job job = jobs.insert(new Job(
                    null,
                    null,
                    MediaMetadataJobDefinition.JOB_TYPE,
                    MediaMetadataJobDefinition.EXECUTION_VERSION,
                    "PENDING",
                    MediaMetadataJobDefinition.IMAGE_METADATA_STAGE,
                    0,
                    null,
                    0,
                    createdAtMs,
                    null,
                    null,
                    null));
            JobStage stage = jobs.insert(new JobStage(
                    null,
                    job.id(),
                    MediaMetadataJobDefinition.IMAGE_METADATA_STAGE,
                    null,
                    "PENDING",
                    0,
                    null,
                    0,
                    createdAtMs,
                    null,
                    null,
                    null));
            return new MediaMetadataExecutionDetails(job, List.of(stage));
        }
    }
}
