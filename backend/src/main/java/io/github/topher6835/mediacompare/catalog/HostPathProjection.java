package io.github.topher6835.mediacompare.catalog;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;

/** Portable display projection only. Null means the retained route is inconsistent or invalid. */
public final class HostPathProjection {
    private HostPathProjection() {}

    public static String from(String rootPath, String rootPathKey, String rootDialect, String relativePath,
            String locationPath, String locationKey) {
        try {
            LocationDialect dialect = LocationDialect.fromPersistedName(rootDialect);
            var root = LocationPathParser.parse(dialect, rootPath);
            if (!LocationKeyCodec.matches(root, LocationKey.parse(rootPathKey))) return null;
            var location = new LocationPathCodec().decode(locationPath);
            if (!LocationKeyCodec.matches(location, LocationKey.parse(locationKey))
                    || !relativePath.equals(SourceRelativePath.from(root, location))) return null;
            return format(location);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static String format(LocationPath location) {
        return switch (location.dialect()) {
            case UNIX -> "/" + String.join("/", location.components());
            case WINDOWS_DRIVE -> location.rootFields().getFirst() + ":\\"
                    + String.join("\\", location.components());
            case WINDOWS_UNC -> "\\\\" + String.join("\\", location.rootFields())
                    + (location.components().isEmpty() ? "" : "\\" + String.join("\\", location.components()));
        };
    }
}
