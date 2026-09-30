package io.github.topher6835.mediacompare.catalog;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

class HostPathProjectionTests {
    @Test
    void derivesOnlyAConsistentStructuredLocalPath() {
        var root = LocationPathParser.parse(LocationDialect.UNIX, "/Pictures/Archive");
        var file = root.append("set").append("image.HEIC");
        String rootKey = LocationKeyCodec.encode(root).value();
        String path = new LocationPathCodec().encode(file);
        String key = LocationKeyCodec.encode(file).value();
        assertEquals("/Pictures/Archive/set/image.HEIC", HostPathProjection.from(
                "/Pictures/Archive", rootKey, "unix", "set/image.HEIC", path, key));
        assertNull(HostPathProjection.from("/Pictures/Archive", "invalid", "unix", "set/image.HEIC", path, key));
        assertNull(HostPathProjection.from("/Pictures/Archive", rootKey, "unix", "../image.HEIC", path, key));
        assertNull(HostPathProjection.from("/Pictures/Archive", rootKey, "unix", "set/image.HEIC", path, "invalid"));
        assertNull(HostPathProjection.from("/Pictures/Archive", rootKey, "win-drive", "set/image.HEIC", path, key));
    }
}
