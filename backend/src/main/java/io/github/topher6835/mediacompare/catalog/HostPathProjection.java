package io.github.topher6835.mediacompare.catalog;

import java.nio.file.Path;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;

/** Display projection only. Null means the retained route cannot safely form a local host path. */
public final class HostPathProjection {
    private HostPathProjection() {}

    public static String from(String rootPath, String rootPathKey, String rootDialect, String relativePath,
            String locationPath, String locationKey) {
        try {
            LocationDialect dialect = LocationDialect.fromPersistedName(rootDialect);
            if (dialect != LocationDialect.UNIX) return null;
            var root = LocationPathParser.parse(dialect, rootPath);
            if (!LocationKeyCodec.matches(root, LocationKey.parse(rootPathKey))) return null;
            var location = new LocationPathCodec().decode(locationPath);
            if (!LocationKeyCodec.matches(location, LocationKey.parse(locationKey))
                    || !relativePath.equals(SourceRelativePath.from(root, location))) return null;
            Path path = Path.of("/");
            for (String component : location.components()) path = path.resolve(component);
            return path.toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }
}
