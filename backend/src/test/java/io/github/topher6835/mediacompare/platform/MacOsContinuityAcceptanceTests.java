package io.github.topher6835.mediacompare.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
class MacOsContinuityAcceptanceTests {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @TempDir Path directory;

    @Test
    void directoryObjectEvidenceIsStableAcrossReadsProcessesAndChildChanges() throws Exception {
        Path anchor = Files.createDirectory(directory.resolve("Anchor"));
        Path source = Files.createDirectory(anchor.resolve("Photos"));

        MacOsContinuityEvidence anchorBefore = MacOsContinuityEvidence.capture(anchor);
        MacOsContinuityEvidence sourceBefore = MacOsContinuityEvidence.capture(source);
        assertEquals(anchorBefore.contextRequiredEvidence(),
                MacOsContinuityEvidence.capture(anchor).contextRequiredEvidence());
        assertEquals(sourceBefore.sourceRequiredEvidence(),
                MacOsContinuityEvidence.capture(source).sourceRequiredEvidence());
        assertEquals(sourceBefore.sourceRequiredEvidence(),
                runInHelperJvm(source).sourceRequiredEvidence());

        Path child = Files.writeString(source.resolve("changing.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(child, "two", StandardCharsets.UTF_8);
        Files.delete(child);

        MacOsContinuityEvidence sourceAfter = MacOsContinuityEvidence.capture(source);
        assertEquals(sourceBefore.sourceRequiredEvidence(), sourceAfter.sourceRequiredEvidence());
        System.out.printf("macOS temp filesystem: provider=%s storeName=%s storeType=%s%n",
                sourceAfter.providerClass(), sourceAfter.fileStoreName(), sourceAfter.fileStoreType());
    }

    @Test
    void hardLinksShareObjectEvidenceButRemainDistinctPathLocations() throws Exception {
        Path first = Files.writeString(directory.resolve("first.dat"), "same", StandardCharsets.UTF_8);
        Path second = directory.resolve("second.dat");
        try {
            Files.createLink(second, first);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Hard links are unavailable: " + exception.getMessage());
        }

        MacOsContinuityEvidence firstEvidence = MacOsContinuityEvidence.capture(first);
        MacOsContinuityEvidence secondEvidence = MacOsContinuityEvidence.capture(second);
        assertTrue(Files.isSameFile(first, second));
        assertEquals(firstEvidence.device(), secondEvidence.device());
        assertEquals(firstEvidence.inode(), secondEvidence.inode());
        assertEquals(firstEvidence.creationTimeNanos(), secondEvidence.creationTimeNanos());
        assertNotEquals(first.getFileName().toString(), second.getFileName().toString());
    }

    @Test
    void enumerationCharacterizesExactCaseAndUnicodeSpelling() throws Exception {
        String exactName = "CaseProbe.txt";
        Path exact = Files.writeString(directory.resolve(exactName), "case", StandardCharsets.UTF_8);
        List<String> names = listNames(directory);
        assertTrue(names.contains(exactName), "directory enumeration did not preserve the created spelling");

        Path alternateCase = directory.resolve("caseprobe.txt");
        boolean caseInsensitive = Files.exists(alternateCase) && Files.isSameFile(exact, alternateCase);

        String composedName = "Caf\u00e9.txt";
        String decomposedName = Normalizer.normalize(composedName, Normalizer.Form.NFD);
        Path composed = Files.writeString(directory.resolve(composedName), "unicode", StandardCharsets.UTF_8);
        String observedUnicode;
        try (var paths = Files.list(directory)) {
            observedUnicode = paths
                    .filter(path -> {
                        try {
                            return Files.isSameFile(path, composed);
                        } catch (IOException exception) {
                            return false;
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .findFirst()
                    .orElseThrow();
        }
        boolean normalizationEquivalent = Files.exists(directory.resolve(decomposedName))
                && Files.isSameFile(composed, directory.resolve(decomposedName));

        assertFalse(observedUnicode.isEmpty());
        System.out.printf(
                "macOS temp filesystem characterization: caseInsensitive=%s unicodeLookupEquivalent=%s "
                        + "createdNfc=%s observedNfc=%s%n",
                caseInsensitive,
                normalizationEquivalent,
                Normalizer.isNormalized(composedName, Normalizer.Form.NFC),
                Normalizer.isNormalized(observedUnicode, Normalizer.Form.NFC));
    }

    @Test
    void noFollowChecksDetectRootAncestorAndDescendantSymlinks() throws Exception {
        Path realRoot = Files.createDirectory(directory.resolve("real-root"));
        Path realSource = Files.createDirectory(realRoot.resolve("source"));
        Path linkedRoot = directory.resolve("linked-root");
        try {
            Files.createSymbolicLink(linkedRoot, realRoot);
            Files.createSymbolicLink(realSource.resolve("file-link"), directory.resolve("missing-target"));
            Files.createSymbolicLink(realSource.resolve("directory-link"), realRoot);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertTrue(Files.readAttributes(linkedRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .isSymbolicLink());
        assertTrue(hasSymbolicLinkInExistingPath(linkedRoot.resolve("source")));

        CoverageResult coverage = walkWithoutFollowingLinks(realSource, false);
        assertFalse(coverage.complete());
        assertEquals(2, coverage.symbolicLinks().size());

        Path ordinary = Files.createDirectory(directory.resolve("ordinary"));
        Files.writeString(ordinary.resolve("file"), "ordinary", StandardCharsets.UTF_8);
        assertTrue(walkWithoutFollowingLinks(ordinary, false).complete());
    }

    @Test
    void positiveObservationsDoNotImplyCoverageAfterTraversalFailure() throws Exception {
        Path root = Files.createDirectory(directory.resolve("partial"));
        Files.writeString(root.resolve("first"), "one", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("second"), "two", StandardCharsets.UTF_8);

        CoverageResult result = walkWithoutFollowingLinks(root, true);
        assertFalse(result.complete());
        assertEquals(1, result.regularFiles().size());
        assertNotNull(result.failure());
    }

    private MacOsContinuityEvidence runInHelperJvm(Path path) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path output = directory.resolve("helper-output.txt");
        Process process = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                MacOsContinuityProbeMain.class.getName(),
                path.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "continuity helper JVM timed out");
            String processOutput = readBounded(output);
            assertEquals(0, process.exitValue(), processOutput);
            return MacOsContinuityEvidence.decode(processOutput.strip());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static List<String> listNames(Path parent) throws IOException {
        try (var paths = Files.list(parent)) {
            return paths.map(path -> path.getFileName().toString()).toList();
        }
    }

    private static boolean hasSymbolicLinkInExistingPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .isSymbolicLink()) {
                return true;
            }
        }
        return false;
    }

    private static CoverageResult walkWithoutFollowingLinks(Path root, boolean failAfterFirstFile) {
        List<Path> regularFiles = new ArrayList<>();
        List<Path> symbolicLinks = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink()) {
                        symbolicLinks.add(file);
                    } else if (attributes.isRegularFile()) {
                        regularFiles.add(file);
                        if (failAfterFirstFile) {
                            throw new IOException("injected traversal failure after positive observation");
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return new CoverageResult(regularFiles, symbolicLinks, symbolicLinks.isEmpty(), null);
        } catch (IOException exception) {
            return new CoverageResult(regularFiles, symbolicLinks, false, exception);
        }
    }

    private static String readBounded(Path path) throws IOException {
        long size = Files.size(path);
        assertTrue(size <= 64 * 1024, "helper output exceeded 64 KiB");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private record CoverageResult(
            List<Path> regularFiles,
            List<Path> symbolicLinks,
            boolean complete,
            IOException failure) {
    }
}
