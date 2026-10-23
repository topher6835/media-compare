package io.github.topher6835.mediacompare.analysis;

import io.github.topher6835.mediacompare.contentread.ExfatContentReadBundles;
import io.github.topher6835.mediacompare.web.CreateMediaMetadataRunRequest;

import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanOptions;
import io.github.topher6835.mediacompare.scan.ScanExecutionDefinition;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.filesystem.WindowsDurableEvidenceFormat;

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

    private ExfatContentReadBundles contentBundles;
    @org.springframework.beans.factory.annotation.Autowired
    void contentBundles(ExfatContentReadBundles bundles) { contentBundles = bundles; }

    public ExfatContentReadBundles.MetadataAdmission create(
            CreateMediaMetadataRunRequest request) {
        admissionLock.lock();
        try {
            var nativeLimited = transactions.createLimitedNative(request.indexingScanRunId());
            return nativeLimited != null ? nativeLimited : contentBundles.admitMetadata(request, transactions::create);
        }
        finally { admissionLock.unlock(); }
    }

    public Long limitedScanId(CreateMediaMetadataRunRequest request) {
        return transactions.limitedScanId(request.indexingScanRunId());
    }

    @Component
    public static class TransactionalAcceptance {

        private final JobRepository jobs;
        private final ScanRepository scans;
        private final CatalogRepository catalog;

        public TransactionalAcceptance(JobRepository jobs,
                ScanRepository scans,
                CatalogRepository catalog) {
            this.jobs = jobs; this.scans = scans; this.catalog = catalog;
        }

        public Long limitedScanId(Long id) {
            if (id == null) return null;
            var scan = scans.findScanRunById(id).orElseThrow();
            return ScanOptions.from(scan).includeSubfolders() ? null : id;
        }

        @Transactional
        public ExfatContentReadBundles.MetadataAdmission createLimitedNative(Long id) {
            if (id == null) return null;
            jobs.reserveAdmissionWrite();
            if (limitedScanId(id) == null) return null;
            var scan = scans.findScanRunById(id).orElseThrow();
            var execution = jobs.findJobByScanRunIdAndTypeAndExecutionVersion(id, "SCAN", ScanExecutionDefinition.VERSION_3).orElseThrow();
            if (!"COMPLETED".equals(scan.status()) || !"COMPLETED".equals(execution.status())) {
                throw new MediaMetadataJobConflictException("Limited scan must finish before metadata");
            }
            var sources = scans.findScanRunSourcesByScanRunId(id);
            if (sources.isEmpty()) throw new MediaMetadataJobConflictException("Scan has no Sources");
            for (var row : sources) {
                var source = catalog.findSourceById(row.sourceId()).orElseThrow();
                if ("win-drive".equals(source.rootPathDialect())
                        && WindowsDurableEvidenceFormat.identify(source.bindingEvidenceJson())
                        == WindowsDurableEvidenceFormat.EXFAT_SOURCE) return null;
            }
            return new ExfatContentReadBundles.MetadataAdmission(create(), true);
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
