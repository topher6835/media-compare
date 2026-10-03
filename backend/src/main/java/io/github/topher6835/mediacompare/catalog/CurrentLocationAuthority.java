package io.github.topher6835.mediacompare.catalog;

import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsDurableEvidenceFormat;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;

/** Checks stored Source/context authority without treating a catalog path as host access. */
public final class CurrentLocationAuthority {
    private static final WindowsNtfsEvidenceCodec WINDOWS = new WindowsNtfsEvidenceCodec();

    private CurrentLocationAuthority() { }

    public static Route requirePersisted(Source source, LocationContext context) {
        if (context == null || !context.id().equals(source.boundLocationContextId())
                || context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.ACCEPTED) {
            throw new IllegalArgumentException("Source lacks current accepted LocationContext");
        }
        if (LocationDialect.UNIX.persistedName().equals(source.rootPathDialect())) {
            var acceptance = LocationContextAcceptanceAuthority.requireCurrentAccepted(context);
            var binding = SourceBindingAuthority.requireCurrentBound(source);
            var anchor = acceptance.macOsApfsEvidence();
            var root = binding.macOsApfsSourceRootEvidence();
            if (root.locationContextRevision() != context.revision()
                    || !anchor.volumeUuid().equals(root.volumeUuid())
                    || !anchor.anchorLocationPath().contains(root.rootLocationPath())) {
                throw new IllegalStateException("APFS Source/context evidence disagrees");
            }
            return new Route(root.rootLocationPath(), anchor.anchorLocationPath(),
                    "apfs", anchor.volumeUuid());
        }
        if (LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())) {
            var profile = WindowsDurableEvidenceFormat.sourceProfile(source.bindingEvidenceJson());
            if (profile != WindowsDurableEvidenceFormat.contextProfile(context.continuityEvidenceJson())) {
                throw new IllegalStateException("Windows Source/context evidence profiles disagree");
            }
            if (profile == FileSystemProfile.EXFAT) {
                var codec = new io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec();
                var anchor = codec.decodeContext(context.continuityEvidenceJson());
                var root = codec.decodeSource(source.bindingEvidenceJson());
                var paths = new io.github.topher6835.mediacompare.location.LocationPathCodec();
                LocationPath resolved = paths.decode(root.resolvedRootLocationPath());
                LocationPath domain = paths.decode(anchor.anchorLocationPath());
                if (!anchor.contextId().equals(context.id()) || anchor.contextRevision() != context.revision()
                        || !anchor.anchorLocationPath().equals(context.anchorLocationPath())
                        || !anchor.anchorLocationKey().equals(context.anchorLocationKey())
                        || root.sourceId() != source.id() || root.sourceRevision() != source.locationRevision()
                        || !root.contextId().equals(context.id()) || root.contextRevision() != context.revision()
                        || !root.configuredRoot().equals(source.rootPath())
                        || !root.configuredRootLocationKey().equals(source.rootPathKey())
                        || !anchor.volume().equals(root.volume()) || !domain.contains(resolved)) {
                    throw new IllegalStateException("exFAT Source/context configuration disagrees");
                }
                return new Route(resolved, domain, "exfat", anchor.volume().volumeSerial());
            }
            var anchor = WINDOWS.decodeContext(context.continuityEvidenceJson());
            var root = WINDOWS.decodeSource(source.bindingEvidenceJson());
            LocationPath configured = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, source.rootPath());
            if (!LocationKeyCodec.matches(configured, LocationKey.parse(source.rootPathKey()))
                    || !anchor.contextId().equals(context.id()) || anchor.contextRevision() != context.revision()
                    || !anchor.anchor().equals(io.github.topher6835.mediacompare.location.LocationAnchorPolicy
                            .validateAnchor(context.anchorLocationPath(), context.anchorLocationKey()))
                    || root.sourceId() != source.id() || root.sourceRevision() != source.locationRevision()
                    || !root.contextId().equals(context.id()) || root.contextRevision() != context.revision()
                    || !root.root().equals(configured) || !anchor.anchor().contains(root.root())
                    || !anchor.identity().volumeSerial().equals(root.identity().volumeSerial())) {
                throw new IllegalStateException("NTFS Source/context evidence disagrees");
            }
            return new Route(root.root(), anchor.anchor(), "ntfs", anchor.identity().volumeSerial());
        }
        throw new IllegalArgumentException("Unsupported Source authority profile");
    }

    public static Route requireCurrentHost(Source source, LocationContext context) {
        Route route = requirePersisted(source, context);
        boolean supported = switch (route.fileSystemType()) {
            case "apfs" -> HostFileSystems.current().supportsProfile(FileSystemProfile.APFS);
            case "ntfs" -> HostFileSystems.current().supportsProfile(FileSystemProfile.NTFS);
            default -> false;
        };
        if (!supported) throw new IllegalArgumentException("Source authority is foreign to current host");
        return route;
    }

    public record Route(LocationPath root, LocationPath anchor, String fileSystemType, String volumeId) { }
}
