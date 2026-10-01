package io.github.topher6835.mediacompare.catalog;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

class HostPathProjectionTests {
    @Test
    void rendersAConsistentUnixLocation() {
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

    @Test
    void rendersAWindowsDriveLocationIndependentlyOfTheCurrentHost() {
        var root = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "D:\\Photos");
        var file = root.append("A.jpg");
        String rootKey = LocationKeyCodec.encode(root).value();
        String path = new LocationPathCodec().encode(file);
        String key = LocationKeyCodec.encode(file).value();

        assertEquals("D:\\Photos\\A.jpg", HostPathProjection.from(
                "D:\\Photos", rootKey, "win-drive", "A.jpg", path, key));
        assertNull(HostPathProjection.from("D:\\Photos", rootKey, "win-drive", "B.jpg", path, key));
        assertNull(HostPathProjection.from("D:\\Photos", rootKey, "win-drive", "A.jpg", path, "invalid"));
    }

    @Test
    void rendersAConsistentUncLocationWithoutHostAccess() {
        var root = LocationPathParser.parse(LocationDialect.WINDOWS_UNC, "\\\\server\\share\\Photos");
        var file = root.append("A.jpg");

        assertEquals("\\\\server\\share\\Photos\\A.jpg", HostPathProjection.from(
                "\\\\server\\share\\Photos", LocationKeyCodec.encode(root).value(), "win-unc", "A.jpg",
                new LocationPathCodec().encode(file), LocationKeyCodec.encode(file).value()));
    }
}
