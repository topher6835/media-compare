package io.github.topher6835.mediacompare.filesystem;

import java.util.Objects;
import io.github.topher6835.mediacompare.catalog.CurrentLocationAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriod;

/** Exact durable snapshots, including the open binding period. A Source ID alone is insufficient. */
public record ExfatAuthorityScope(Source source, LocationContext context, SourceBindingPeriod period) {
    public ExfatAuthorityScope {
        Objects.requireNonNull(source);
        Objects.requireNonNull(context);
        Objects.requireNonNull(period);
        if (!"exfat".equals(CurrentLocationAuthority.requirePersisted(source, context).fileSystemType())
                || period.id() == null || period.sourceId() != source.id()
                || period.boundSourceLocationRevision() != source.locationRevision()
                || !Objects.equals(period.locationContextId(), context.id())
                || !Objects.equals(period.rootPathDialect(), source.rootPathDialect())
                || !Objects.equals(period.rootPath(), source.rootPath())
                || !Objects.equals(period.rootPathKey(), source.rootPathKey())
                || !Objects.equals(period.bindingEvidenceJson(), source.bindingEvidenceJson())
                || period.unboundAtMs() != null || period.unboundSourceLocationRevision() != null
                || period.boundAtMs() != new WindowsExfatEvidenceCodec()
                        .decodeSource(source.bindingEvidenceJson()).boundAtMs()
                || period.boundAtMs() > source.updatedAtMs()) {
            throw new IllegalArgumentException("Invalid exFAT open binding snapshot");
        }
    }
}
