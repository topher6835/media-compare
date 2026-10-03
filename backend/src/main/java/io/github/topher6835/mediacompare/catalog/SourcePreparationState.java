package io.github.topher6835.mediacompare.catalog;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsDurableEvidenceFormat;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;

/** Product state derived from the current durable Source binding shape. */
public enum SourcePreparationState {
    PREPARATION_REQUIRED,
    READY,
    REBIND_REQUIRED;

    public static SourcePreparationState from(Source source) {
        Objects.requireNonNull(source, "Source");
        if (source.boundLocationContextId() != null) {
            // A foreign-host Source remains catalog-visible; live use is checked separately.
            if (LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())) {
                if (WindowsDurableEvidenceFormat.sourceProfile(source.bindingEvidenceJson()) == FileSystemProfile.EXFAT) {
                    var evidence = new io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec()
                            .decodeSource(source.bindingEvidenceJson());
                    if (source.id() == null || evidence.sourceId() != source.id()
                            || evidence.sourceRevision() != source.locationRevision()
                            || !evidence.contextId().equals(source.boundLocationContextId())
                            || !evidence.configuredRoot().equals(source.rootPath())
                            || !evidence.configuredRootLocationKey().equals(source.rootPathKey())) {
                        throw new IllegalStateException("Invalid bound exFAT Source configuration");
                    }
                    return READY;
                }
                var root = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, source.rootPath());
                var evidence = new WindowsNtfsEvidenceCodec().decodeSource(source.bindingEvidenceJson());
                if (!LocationKeyCodec.matches(root, LocationKey.parse(source.rootPathKey()))
                        || source.id() == null || evidence.sourceId() != source.id()
                        || evidence.sourceRevision() != source.locationRevision()
                        || !evidence.contextId().equals(source.boundLocationContextId())
                        || !evidence.root().equals(root)) {
                    throw new IllegalStateException("Invalid bound Windows Source root");
                }
            } else {
                SourceBindingAuthority.requireCurrentBound(source);
            }
            return READY;
        }
        if (source.bindingEvidenceJson() != null) {
            throw new IllegalStateException("Unbound Source has binding evidence");
        }
        if (source.rootPathDialect() == null) {
            if (!Objects.equals(source.rootPathKey(), source.rootPath())) {
                throw new IllegalStateException("Unprepared Source root and key disagree");
            }
            return PREPARATION_REQUIRED;
        }
        try {
            LocationDialect dialect = LocationDialect.fromPersistedName(source.rootPathDialect());
            if (dialect != LocationDialect.UNIX && dialect != LocationDialect.WINDOWS_DRIVE) {
                throw new IllegalArgumentException("Unsupported structured Source dialect");
            }
            LocationPath root = LocationPathParser.parse(dialect, source.rootPath());
            if (!LocationKeyCodec.matches(root, LocationKey.parse(source.rootPathKey()))) {
                throw new IllegalArgumentException("Structured Source root and key disagree");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid structured-unbound Source", exception);
        }
        return REBIND_REQUIRED;
    }
}
