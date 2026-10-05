package io.github.topher6835.mediacompare.contentread;

import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityScope;
import io.github.topher6835.mediacompare.location.*;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;
import io.github.topher6835.mediacompare.job.JobRepository;
import org.springframework.stereotype.Component;

/** Catalog-only later-read guard. Never claims historical physical continuity. */
@Component
public class ExfatContentReadCatalog {
    private final CatalogRepository catalog;
    private final SourceMembershipRepository memberships;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final AnalysisRepository analyses;
    private final JobRepository jobs;

    public ExfatContentReadCatalog(CatalogRepository catalog, SourceMembershipRepository memberships,
            LocationContextRepository contexts, SourceBindingPeriodRepository periods,
            AnalysisRepository analyses, JobRepository jobs) {
        this.catalog = catalog; this.memberships = memberships; this.contexts = contexts;
        this.periods = periods; this.analyses = analyses; this.jobs = jobs;
    }

    public ExfatAuthorityScope scope(long sourceId) {
        Source source = catalog.findSourceById(sourceId).orElseThrow();
        return new ExfatAuthorityScope(source, contexts.findById(source.boundLocationContextId()).orElseThrow(),
                periods.findOpenBySourceId(sourceId).orElseThrow());
    }

    public ExfatContentReadCapture capture(ExfatContentReadAuthority owner, long fileId, long membershipId) {
        FileEntry file = memberships.findById(fileId).orElseThrow();
        SourceMembership member = memberships.findMembershipById(membershipId).orElseThrow();
        ContentRecord content = catalog.findContentRecordById(file.currentContentId()).orElseThrow();
        AnalysisRecord analysis = analyses.findAnalysisRecordByCacheKey(content.id(),
                Sha256AnalysisDefinition.ANALYSIS_TYPE, Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION, Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH).orElseThrow(ExfatContentReadCatalog::unavailable);
        ContentHash hash = analyses.findContentHash(analysis.id()).orElseThrow(ExfatContentReadCatalog::unavailable);
        var capture = new ExfatContentReadCapture(owner, file, member, content, analysis, hash);
        require(capture, false);
        return capture;
    }

    /** Writer reservation is first when publishing, after the caller's runtime gate. */
    public void require(ExfatContentReadCapture capture, boolean publishing) {
        if (publishing) contexts.reserveWrite();
        var owner = capture.authority();
        var scope = owner.scope();
        var file = capture.file();
        var member = capture.membership();
        var content = capture.content();
        var analysis = capture.shaAnalysis();
        var hash = capture.sha();
        if (!scope(owner.window().sourceId()).equals(scope)
                || !memberships.findById(file.id()).orElseThrow().equals(file)
                || !memberships.findMembershipById(member.id()).orElseThrow().equals(member)
                || !catalog.findContentRecordById(content.id()).orElseThrow().equals(content)
                || !analyses.findAnalysisRecordById(analysis.id()).orElseThrow().equals(analysis)
                || !analyses.findContentHash(analysis.id()).orElseThrow().equals(hash)
                || !CurrentMembershipAuthority.isCurrentRoute(file, member, scope.source(), scope.context())
                || !Long.valueOf(content.id()).equals(file.currentContentId())
                || content.sizeBytes() != file.sizeBytes()) throw unavailable();
        var receipt = OccurrenceProfileValidation.requireExfat(file);
        if (receipt.byteCount() != content.sizeBytes() || !receipt.sha256().equals(hash.digestHex())
                || analysis.contentRecordId() != content.id() || !"COMPLETED".equals(analysis.status())
                || !Sha256AnalysisDefinition.ANALYSIS_TYPE.equals(analysis.analysisType())
                || !Sha256AnalysisDefinition.ANALYZER_ID.equals(analysis.analyzerId())
                || !Sha256AnalysisDefinition.ANALYZER_VERSION.equals(analysis.analyzerVersion())
                || Sha256AnalysisDefinition.CONFIGURATION_VERSION != analysis.configurationVersion()
                || !Sha256AnalysisDefinition.CONFIGURATION_JSON.equals(analysis.configurationJson())
                || !Sha256AnalysisDefinition.CONFIGURATION_HASH.equals(analysis.configurationHash())
                || hash.analysisRecordId() != analysis.id()
                || !Sha256AnalysisDefinition.ALGORITHM.equals(hash.algorithm())
                || !Sha256AnalysisDefinition.isValidDigest(hash.digestHex())) throw unavailable();
        var route = CurrentLocationAuthority.requirePersisted(scope.source(), scope.context());
        var path = new LocationPathCodec().decode(file.locationPath());
        if (!"exfat".equals(route.fileSystemType()) || !route.root().contains(path)
                || !route.anchor().contains(path) || !LocationKeyCodec.matches(path, LocationKey.parse(file.locationKey()))
                || !SourceRelativePath.from(route.root(), path).equals(member.relativePath())
                || !member.relativePath().equals(member.pathKey())) throw unavailable();
        if (owner.metadataJobId() != null) {
            var job = jobs.findJobById(owner.metadataJobId()).orElseThrow();
            if (job.scanRunId() != null || !MediaMetadataJobDefinition.JOB_TYPE.equals(job.jobType())
                    || job.executionVersion() != MediaMetadataJobDefinition.EXECUTION_VERSION
                    || !MediaMetadataJobDefinition.IMAGE_METADATA_STAGE.equals(job.currentStageType())
                    || !(publishing ? "RUNNING".equals(job.status())
                        : java.util.List.of("PENDING", "RUNNING").contains(job.status()))) throw unavailable();
            var stage = jobs.findJobStageByJobIdAndType(job.id(), MediaMetadataJobDefinition.IMAGE_METADATA_STAGE).orElseThrow();
            if (!job.status().equals(stage.status())) throw unavailable();
        }
    }

    public static IllegalStateException unavailable() {
        return new IllegalStateException("Exact exFAT content-read authority/evidence unavailable");
    }
}
