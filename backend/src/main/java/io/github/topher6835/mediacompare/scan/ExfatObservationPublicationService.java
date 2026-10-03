package io.github.topher6835.mediacompare.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.topher6835.mediacompare.catalog.ExfatOccurrenceRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.filesystem.ExfatObservationReceipt;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;
import io.github.topher6835.mediacompare.scan.authority.WindowsExfatScanAuthority;
import io.github.topher6835.mediacompare.job.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Hash IO has finished, but the same file and its ancestry remain held through this commit. */
@Service
public class ExfatObservationPublicationService {
    public record Observation(LocationPath path, long byteCount, String sha256,
            ExfatObservationReceipt.ClassificationEvidence before,
            ExfatObservationReceipt.ClassificationEvidence after, long startedAtMs, long finishedAtMs) { }
    private final ExfatScanBundles bundles;
    private final ExfatOccurrenceRepository occurrences;
    private final SourceMembershipPublicationService memberships;
    private final JobRepository jobs;
    private final TransactionTemplate transactions;

    public ExfatObservationPublicationService(ExfatScanBundles bundles, ExfatOccurrenceRepository occurrences,
            SourceMembershipPublicationService memberships, JobRepository jobs, PlatformTransactionManager manager) {
        this.bundles = bundles; this.occurrences = occurrences; this.memberships = memberships;
        this.jobs = jobs; transactions = new TransactionTemplate(manager);
    }

    public FileEntry publish(Observation observation, WindowsExfatDiscoveryAccess.File held,
            List<WindowsExfatScanAuthority> participants, long progress) {
        var ordered = participants.stream().sorted(java.util.Comparator.comparingLong(a -> a.scope().source().id())).toList();
        if (ordered.isEmpty()) throw new IllegalArgumentException("Positive has no participating Source");
        try (var lease = bundles.lease(ordered)) {
            lease.checkpoint();
            return transactions.execute(status -> {
                var ids = occurrences.reserveIds(ordered.size());
                lease.checkpoint();
                if (!held.channel().isOpen()) throw new IllegalStateException("Protected file closed before publication");
                for (var a : ordered) bundles.requireCatalog(a, ScanExecutionDefinition.DISCOVERY, "DISCOVERING");
                var first = ordered.getFirst();
                var sourceRows = new ArrayList<ExfatObservationReceipt.SourceEntry>();
                var paths = new LocationPathCodec();
                String path = paths.encode(observation.path());
                String key = LocationKeyCodec.encode(observation.path()).value();
                for (int i = 0; i < ordered.size(); i++) {
                    var a = ordered.get(i);
                    if (!first.bundleUuid().equals(a.bundleUuid()) || first.scanRunId() != a.scanRunId()
                            || first.jobId() != a.jobId() || !first.scope().context().equals(a.scope().context())
                            || !a.root().contains(observation.path())) throw new IllegalStateException("Foreign positive participant");
                    sourceRows.add(new ExfatObservationReceipt.SourceEntry(a.scope().source().id(),
                            a.scope().source().locationRevision(), paths.encode(a.root()), LocationKeyCodec.encode(a.root()).value(),
                            a.window().windowId(), ids.membershipIds().get(i), 0, a.scanRunSourceId(), a.generation(),
                            SourceRelativePath.from(a.root(), observation.path())));
                }
                var evidence = new WindowsExfatEvidenceCodec().decodeContext(first.scope().context().continuityEvidenceJson());
                if (!evidence.volume().volumeSerial().equals(observation.before().volumeSerial())
                        || !evidence.volume().volumeGuid().equals(observation.before().volumeGuid())) {
                    throw new IllegalStateException("Positive volume disagrees with accepted context");
                }
                var modified = java.time.Instant.ofEpochSecond(observation.before().nioModifiedTimeEpochSecond(),
                        observation.before().nioModifiedTimeNano());
                var receipt = new ExfatObservationReceipt(ExfatObservationReceipt.VERSION, UUID.randomUUID().toString(),
                        0, path, key, first.scope().context().id(), first.scope().context().revision(),
                        observation.before().nioSizeBytes(), observation.byteCount(), observation.sha256(), modified.getEpochSecond(),
                        modified.getNano(), observation.before(), observation.after(), observation.startedAtMs(), observation.finishedAtMs(),
                        first.bundleUuid(), first.scanRunId(), first.jobId(), sourceRows);
                FileEntry result = memberships.publishExfat(ids.fileEntryId(), receipt);
                long stageId = jobs.findJobStageByJobIdAndType(first.jobId(), ScanExecutionDefinition.DISCOVERY).orElseThrow().id();
                if (jobs.updateDiscoveryProgress(first.jobId(), stageId, 3, progress) != 2) {
                    throw new IllegalStateException("Could not publish physical observation progress");
                }
                lease.checkpoint();
                return result;
            });
        }
    }
}
