package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MacOsDiskutilPlistParserTests {

    private final MacOsDiskutilPlistParser parser = new MacOsDiskutilPlistParser();

    @Test
    void parsesRequiredApfsFieldsAndCanonicalizesVolumeUuid() {
        MacOsDiskutilPlistParser.DiskutilInfo result = parser.parse(plist(
                "<key>FilesystemType</key><string>apfs</string>"
                        + "<key>VolumeUUID</key><string>AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE</string>"
                        + "<key>Irrelevant</key><integer>7</integer>"));

        assertEquals("apfs", result.fileSystemType());
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", result.volumeUuid());
    }

    @Test
    void rejectsMissingOrWronglyTypedFilesystemType() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(plist(
                        "<key>VolumeUUID</key><string>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</string>")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(plist(
                        "<key>FilesystemType</key><integer>1</integer>"
                                + "<key>VolumeUUID</key>"
                                + "<string>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</string>")));
    }

    @Test
    void distinguishesNonApfsFilesystem() {
        assertThrows(MacOsDiskutilPlistParser.UnsupportedFileSystemException.class,
                () -> parser.parse(plist(
                        "<key>FilesystemType</key><string>hfs</string>"
                                + "<key>VolumeUUID</key>"
                                + "<string>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</string>")));
    }

    @Test
    void rejectsMissingMalformedAndWronglyTypedVolumeUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(plist("<key>FilesystemType</key><string>apfs</string>")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(plist(
                        "<key>FilesystemType</key><string>apfs</string>"
                                + "<key>VolumeUUID</key><string>not-a-uuid</string>")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(plist(
                        "<key>FilesystemType</key><string>apfs</string>"
                                + "<key>VolumeUUID</key><string>1-1-1-1-1</string>")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(plist(
                        "<key>FilesystemType</key><string>apfs</string>"
                                + "<key>VolumeUUID</key><data>AAAA</data>")));
    }

    @Test
    void rejectsMalformedXmlWrongRootDuplicateKeysAndOversizedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("<plist><dict>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("<dict/>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(plist(
                        "<key>FilesystemType</key><string>apfs</string>"
                                + "<key>FilesystemType</key><string>apfs</string>"
                                + "<key>VolumeUUID</key>"
                                + "<string>aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</string>")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(new byte[MacOsDiskutilPlistParser.MAX_PLIST_BYTES + 1]));
    }

    private static byte[] plist(String dictionaryContent) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"
                + "<plist version=\"1.0\"><dict>"
                + dictionaryContent
                + "</dict></plist>").getBytes(StandardCharsets.UTF_8);
    }
}
