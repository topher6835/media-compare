package io.github.topher6835.mediacompare.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

@EnabledOnOs(OS.MAC)
class MacOsMountedVolumeContinuityAcceptanceTests {

    private static final String OPT_IN_PROPERTY = "media-compare.mac-continuity.acceptance";
    private static final Path HDIUTIL = Path.of("/usr/bin/hdiutil");
    private static final Path DISKUTIL = Path.of("/usr/sbin/diskutil");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(45);
    private static final long OUTPUT_LIMIT = 1024 * 1024;

    @TempDir Path directory;

    @Test
    void disposableImagesDistinguishReconnectFallbackReplacementAndSourceRootReplacement() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(OPT_IN_PROPERTY),
                () -> "Opt in with -D" + OPT_IN_PROPERTY + "=true");
        Assumptions.assumeTrue(Files.isExecutable(HDIUTIL), "hdiutil is unavailable");
        Assumptions.assumeTrue(Files.isExecutable(DISKUTIL), "diskutil is unavailable");

        Path mountPoint = directory.toRealPath().resolve("mountpoint");
        requireSafeMountPoint(mountPoint);
        try (DiskImageFixture fixture = new DiskImageFixture(directory, mountPoint)) {
            fixture.createImagesOrSkip();

            fixture.attach(fixture.firstImage());
            Path photos = Files.createDirectory(mountPoint.resolve("Photos"));
            Path documents = Files.createDirectory(mountPoint.resolve("Documents"));
            MountedEvidence initialAnchor = captureMounted(mountPoint);
            MountedEvidence initialPhotos = captureMounted(photos);
            MountedEvidence initialDocuments = captureMounted(documents);
            assertSameSource(initialPhotos, captureMounted(photos), "Photos repeated capture");

            fixture.detach();
            fixture.attach(fixture.firstImage());
            MountedEvidence reconnectAnchor = captureMounted(mountPoint);
            MountedEvidence reconnectPhotos = captureMounted(mountPoint.resolve("Photos"));
            MountedEvidence reconnectDocuments = captureMounted(mountPoint.resolve("Documents"));

            assertSameContext(initialAnchor, reconnectAnchor, "same-image reconnect");
            assertSameSource(initialPhotos, reconnectPhotos, "Photos after same-image reconnect");
            assertSameSource(initialDocuments, reconnectDocuments, "Documents after same-image reconnect");

            List<MountedEvidence> recreatedPhotos = new ArrayList<>();
            for (int attempt = 0; attempt < 8; attempt++) {
                Files.delete(mountPoint.resolve("Photos"));
                Files.createDirectory(mountPoint.resolve("Photos"));
                MountedEvidence recreated = captureMounted(mountPoint.resolve("Photos"));
                assertSourceReplacementRejected(reconnectPhotos, recreated, attempt + 1);
                recreatedPhotos.add(recreated);
            }
            MountedEvidence unchangedDocuments = captureMounted(mountPoint.resolve("Documents"));
            MountedEvidence unchangedAnchor = captureMounted(mountPoint);

            assertSameContext(reconnectAnchor, unchangedAnchor, "source-root-only replacement");
            assertSameSource(reconnectDocuments, unchangedDocuments, "unrelated Source root");
            long reusedInodes = recreatedPhotos.stream()
                    .filter(evidence -> evidence.nio().inode() == reconnectPhotos.nio().inode())
                    .count();

            fixture.detach();
            Files.createDirectories(mountPoint);
            MountedEvidence fallback = captureMounted(mountPoint);
            assertNotEquals(initialAnchor.volumeUuid(), fallback.volumeUuid(),
                    "readable fallback path was not distinguished from the mounted image");

            fixture.attach(fixture.secondImage());
            MountedEvidence replacement = captureMounted(mountPoint);
            assertNotEquals(initialAnchor.volumeUuid(), replacement.volumeUuid(),
                    "replacement image was not distinguished from the original image");
            fixture.detach();

            fixture.attach(fixture.firstImage());
            MountedEvidence returnedOriginal = captureMounted(mountPoint);
            assertSameContext(initialAnchor, returnedOriginal, "return to original image");
            fixture.detach();

            fixture.attach(fixture.caseSensitiveImage());
            Path exactCase = Files.writeString(
                    mountPoint.resolve("CaseProbe.txt"), "case", StandardCharsets.UTF_8);
            assertFalse(Files.exists(mountPoint.resolve("caseprobe.txt")),
                    "case-sensitive APFS fixture resolved alternate case");
            assertTrue(Files.exists(exactCase));
            String composedName = "Caf\u00e9.txt";
            String decomposedName = java.text.Normalizer.normalize(
                    composedName, java.text.Normalizer.Form.NFD);
            Path composed = Files.writeString(
                    mountPoint.resolve(composedName), "unicode", StandardCharsets.UTF_8);
            boolean normalizationEquivalent = Files.exists(mountPoint.resolve(decomposedName))
                    && Files.isSameFile(composed, mountPoint.resolve(decomposedName));
            fixture.detach();

            System.out.printf(
                    "macOS disposable APFS evidence: volumeUuidStable=%s rootInodeStable=%s "
                            + "deviceStable=%s fallbackRejected=true replacementRejected=true "
                            + "sourceRecreations=%d sourceInodeReuses=%d "
                            + "caseSensitiveFixture=true unicodeLookupEquivalentOnCaseSensitive=%s%n",
                    initialAnchor.volumeUuid().equals(reconnectAnchor.volumeUuid()),
                    initialAnchor.nio().inode() == reconnectAnchor.nio().inode(),
                    initialAnchor.nio().device() == reconnectAnchor.nio().device(),
                    recreatedPhotos.size(),
                    reusedInodes,
                    normalizationEquivalent);
        }
    }

    private MountedEvidence captureMounted(Path path) throws Exception {
        MacOsContinuityEvidence nio = MacOsContinuityEvidence.capture(path);
        CommandResult result = runCommand(List.of(
                DISKUTIL.toString(), "info", "-plist", nio.fileStoreName()));
        assertEquals(0, result.exitCode(),
                () -> "diskutil failed: " + result.stderrText() + result.stdoutText());
        String volumeUuid = plistString(result.stdout(), "VolumeUUID");
        assertFalse(volumeUuid.isBlank(), "diskutil did not report VolumeUUID for " + path);
        return new MountedEvidence(volumeUuid, nio);
    }

    private static void assertSameContext(MountedEvidence expected, MountedEvidence actual, String detail) {
        assertEquals(expected.volumeUuid(), actual.volumeUuid(), detail + " changed volume UUID");
        assertEquals(expected.nio().fileStoreType(), actual.nio().fileStoreType(),
                detail + " changed filesystem type");
        assertEquals(expected.nio().inode(), actual.nio().inode(), detail + " changed anchor inode");
        assertTrue(actual.nio().directory(), detail + " is no longer a directory");
        assertFalse(actual.nio().symbolicLink(), detail + " became a symbolic link");
    }

    private static void assertSameSource(MountedEvidence expected, MountedEvidence actual, String detail) {
        assertEquals(expected.volumeUuid(), actual.volumeUuid(), detail + " changed volume UUID");
        assertEquals(expected.nio().fileStoreType(), actual.nio().fileStoreType(),
                detail + " changed filesystem type");
        assertEquals(expected.nio().inode(), actual.nio().inode(), detail + " changed Source-root inode");
        assertEquals(expected.nio().creationTimeNanos(), actual.nio().creationTimeNanos(),
                detail + " changed Source-root creation time");
        assertTrue(actual.nio().directory(), detail + " is no longer a directory");
        assertFalse(actual.nio().symbolicLink(), detail + " became a symbolic link");
    }

    private static void assertSourceReplacementRejected(
            MountedEvidence baseline,
            MountedEvidence recreated,
            int attempt) {
        String detail = "Source-root recreation " + attempt;
        assertEquals(baseline.volumeUuid(), recreated.volumeUuid(),
                detail + " unexpectedly changed volume UUID");
        assertNotEquals(baseline.nio().creationTimeNanos(), recreated.nio().creationTimeNanos(),
                detail + " was not distinguished by creation time");
        assertNotEquals(baseline.nio().sourceRequiredEvidence(), recreated.nio().sourceRequiredEvidence(),
                detail + " matched the baseline required evidence");
    }

    private CommandResult runCommand(List<String> command) throws Exception {
        Path stdout = Files.createTempFile(directory, "mac-continuity-stdout-", ".tmp");
        Path stderr = Files.createTempFile(directory, "mac-continuity-stderr-", ".tmp");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                fail("command timed out: " + command.getFirst());
            }
            requireBounded(stdout);
            requireBounded(stderr);
            return new CommandResult(
                    process.exitValue(),
                    Files.readAllBytes(stdout),
                    Files.readString(stderr, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private static void requireBounded(Path output) throws IOException {
        if (Files.size(output) > OUTPUT_LIMIT) {
            fail("system-tool output exceeded 1 MiB: " + output.getFileName());
        }
    }

    private static String plistString(byte[] plist, String key) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
        Node value = (Node) XPathFactory.newInstance().newXPath().evaluate(
                "//key[text()='" + key + "']/following-sibling::*[1]",
                builder.parse(new ByteArrayInputStream(plist)),
                XPathConstants.NODE);
        if (value == null || !"string".equals(value.getNodeName())) {
            throw new IOException("plist does not contain string key " + key);
        }
        return value.getTextContent();
    }

    private static void requireSafeMountPoint(Path mountPoint) {
        Path normalized = mountPoint.toAbsolutePath().normalize();
        assertTrue(normalized.getNameCount() >= 3, "mountpoint is too broad");
        assertNotEquals(normalized.getRoot(), normalized, "filesystem root cannot be a test mountpoint");
        assertFalse(normalized.startsWith(Path.of("/Volumes/MBP_External")),
                "protected user volume cannot be a test mountpoint");
    }

    private record MountedEvidence(String volumeUuid, MacOsContinuityEvidence nio) {
    }

    private record CommandResult(int exitCode, byte[] stdout, String stderrText) {
        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    private final class DiskImageFixture implements AutoCloseable {
        private final Path mountPoint;
        private final Path firstImage;
        private final Path secondImage;
        private final Path caseSensitiveImage;
        private String attachedDevice;

        private DiskImageFixture(Path parent, Path mountPoint) {
            this.mountPoint = mountPoint;
            this.firstImage = parent.resolve("continuity-a.dmg");
            this.secondImage = parent.resolve("continuity-b.dmg");
            this.caseSensitiveImage = parent.resolve("continuity-case-sensitive.dmg");
        }

        Path firstImage() {
            return firstImage;
        }

        Path secondImage() {
            return secondImage;
        }

        Path caseSensitiveImage() {
            return caseSensitiveImage;
        }

        void createImagesOrSkip() throws Exception {
            CommandResult first = createImage(firstImage, "APFS", "MC_A_" + suffix());
            Assumptions.assumeTrue(first.exitCode() == 0,
                    () -> "Disposable APFS image creation is unavailable: " + first.stderrText());
            CommandResult second = createImage(secondImage, "APFS", "MC_B_" + suffix());
            Assumptions.assumeTrue(second.exitCode() == 0,
                    () -> "Second disposable APFS image creation is unavailable: " + second.stderrText());
            CommandResult caseSensitive = createImage(
                    caseSensitiveImage, "Case-sensitive APFS", "MC_CS_" + suffix());
            Assumptions.assumeTrue(caseSensitive.exitCode() == 0,
                    () -> "Case-sensitive APFS image creation is unavailable: "
                            + caseSensitive.stderrText());
        }

        private CommandResult createImage(Path image, String fileSystem, String volumeName) throws Exception {
            return runCommand(List.of(
                    HDIUTIL.toString(), "create",
                    "-size", "32m",
                    "-fs", fileSystem,
                    "-volname", volumeName,
                    image.toString()));
        }

        void attach(Path image) throws Exception {
            assertNull(attachedDevice, "a disposable image is already attached");
            Files.createDirectories(mountPoint);
            CommandResult result = runCommand(List.of(
                    HDIUTIL.toString(), "attach",
                    "-plist",
                    "-nobrowse",
                    "-noautoopen",
                    "-mountpoint", mountPoint.toString(),
                    image.toString()));
            Assumptions.assumeTrue(result.exitCode() == 0,
                    () -> "Disposable image attach is unavailable: " + result.stderrText());
            try {
                String device = plistString(result.stdout(), "dev-entry");
                assertTrue(device.startsWith("/dev/disk"), "unexpected attached device: " + device);
                attachedDevice = device;
                String actualMountPoint = plistString(result.stdout(), "mount-point");
                assertTrue(Files.isSameFile(mountPoint, Path.of(actualMountPoint)),
                        "image mounted at an unexpected path: " + actualMountPoint);
            } catch (Exception | AssertionError failure) {
                String detachTarget = attachedDevice == null
                        ? mountPoint.toString()
                        : attachedDevice;
                CommandResult cleanup = runCommand(List.of(
                        HDIUTIL.toString(), "detach", detachTarget));
                if (cleanup.exitCode() != 0) {
                    failure.addSuppressed(new IOException(
                            "emergency detach failed: " + cleanup.stderrText()));
                } else {
                    attachedDevice = null;
                }
                throw failure;
            }
        }

        void detach() throws Exception {
            if (attachedDevice == null) {
                return;
            }
            String device = attachedDevice;
            CommandResult result = runCommand(List.of(HDIUTIL.toString(), "detach", device));
            assertEquals(0, result.exitCode(), () -> "hdiutil detach failed: " + result.stderrText());
            attachedDevice = null;
        }

        @Override
        public void close() throws Exception {
            if (attachedDevice == null) {
                return;
            }
            String device = attachedDevice;
            attachedDevice = null;
            CommandResult normal = runCommand(List.of(HDIUTIL.toString(), "detach", device));
            if (normal.exitCode() != 0) {
                CommandResult forced = runCommand(List.of(HDIUTIL.toString(), "detach", "-force", device));
                assertEquals(0, forced.exitCode(),
                        () -> "failed to detach disposable image: " + forced.stderrText());
            }
        }

        private String suffix() {
            return UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
