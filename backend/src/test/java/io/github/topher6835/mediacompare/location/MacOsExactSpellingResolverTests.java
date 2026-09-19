package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MacOsExactSpellingResolverTests {

    @Test
    void resolvesExactAndAlternateSpellingFromEnumeratedEntries() {
        FakeFileSystem files = new FakeFileSystem()
                .directory("Volumes")
                .directory("Volumes", "Archive")
                .directoryWithAliases(List.of("Volumes", "Archive", "Photos"), "photos", "PHOTOS");
        MacOsExactSpellingResolver resolver = resolver(files);

        assertResolved("/Volumes/Archive/Photos", resolver.resolve(unix("/Volumes/Archive/Photos")));
        assertResolved("/Volumes/Archive/Photos", resolver.resolve(unix("/Volumes/Archive/photos")));
    }

    @Test
    void permitsAnOrdinaryFinalFileWhileStillRequiringDirectoryAncestors() {
        FakeFileSystem files = new FakeFileSystem()
                .directory("Volumes")
                .directory("Volumes", "Archive")
                .regularFile("Volumes", "Archive", "photo.jpg");

        assertResolved("/Volumes/Archive/photo.jpg",
                resolver(files).resolve(unix("/Volumes/Archive/photo.jpg")));
    }

    @Test
    void preservesEnumeratedUnicodeWithoutNormalization() {
        String observed = "Cafe\u0301";
        FakeFileSystem files = new FakeFileSystem()
                .directory("Volumes")
                .directory("Volumes", "Archive")
                .directoryWithAliases(List.of("Volumes", "Archive", observed), "Caf\u00e9");

        assertResolved("/Volumes/Archive/" + observed,
                resolver(files).resolve(unix("/Volumes/Archive/Caf\u00e9")));
    }

    @Test
    void usesComponentsRatherThanRawPrefixContainment() {
        FakeFileSystem files = new FakeFileSystem()
                .directory("Volumes")
                .directory("Volumes", "A")
                .directory("Volumes", "AB");

        assertResolved("/Volumes/A", resolver(files).resolve(unix("/Volumes/A")));
        assertResolved("/Volumes/AB", resolver(files).resolve(unix("/Volumes/AB")));
    }

    @Test
    void reportsMissingComponentAsUnavailable() {
        FakeFileSystem files = new FakeFileSystem().directory("Volumes");

        assertFailure(ContinuityOutcome.UNAVAILABLE, ContinuityReason.PROBE_UNAVAILABLE,
                resolver(files).resolve(unix("/Volumes/Missing")));
    }

    @Test
    void rejectsNonDirectoryIntermediateAndSymbolicLinks() {
        FakeFileSystem nonDirectory = new FakeFileSystem()
                .directory("Volumes")
                .regularFile("Volumes", "file");
        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                resolver(nonDirectory).resolve(unix("/Volumes/file/child")));

        FakeFileSystem intermediateLink = new FakeFileSystem()
                .directory("Volumes")
                .symbolicLink("Volumes", "linked")
                .directory("Volumes", "linked", "child");
        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                resolver(intermediateLink).resolve(unix("/Volumes/linked/child")));

        FakeFileSystem finalLink = new FakeFileSystem()
                .directory("Volumes")
                .symbolicLink("Volumes", "linked");
        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                resolver(finalLink).resolve(unix("/Volumes/linked")));
    }

    @Test
    void supportsTheUnixRootAndRejectsOtherDialectsAndHosts() {
        FakeFileSystem files = new FakeFileSystem();
        assertResolved("/", resolver(files).resolve(unix("/")));

        LocationPath windows = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "C:\\Photos");
        assertFailure(ContinuityOutcome.UNSUPPORTED, ContinuityReason.PROFILE_UNSUPPORTED,
                resolver(files).resolve(windows));
        assertFailure(ContinuityOutcome.UNSUPPORTED, ContinuityReason.PROFILE_UNSUPPORTED,
                new MacOsExactSpellingResolver(files, () -> false).resolve(unix("/")));
    }

    private static MacOsExactSpellingResolver resolver(FakeFileSystem files) {
        return new MacOsExactSpellingResolver(files, () -> true);
    }

    private static LocationPath unix(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }

    private static void assertResolved(
            String expected, ContinuityProbeResult<LocationPath> result) {
        assertEquals(ContinuityOutcome.ACCEPTED, result.outcome());
        assertEquals(unix(expected), result.evidence().orElseThrow());
    }

    private static void assertFailure(
            ContinuityOutcome outcome,
            ContinuityReason reason,
            ContinuityProbeResult<?> result) {
        assertEquals(outcome, result.outcome());
        assertEquals(reason, result.reason());
        assertTrue(result.evidence().isEmpty());
    }

    private static final class FakeFileSystem implements MacOsExactSpellingResolver.FileSystemAccess {
        private final Map<List<String>, Entry> entries = new HashMap<>();

        private FakeFileSystem() {
            entries.put(List.of(), new Entry(true, false, Set.of()));
        }

        FakeFileSystem directory(String... components) {
            return add(List.of(components), true, false, Set.of());
        }

        FakeFileSystem directoryWithAliases(List<String> components, String... aliases) {
            return add(components, true, false, Set.of(aliases));
        }

        FakeFileSystem regularFile(String... components) {
            return add(List.of(components), false, false, Set.of());
        }

        FakeFileSystem symbolicLink(String... components) {
            return add(List.of(components), false, true, Set.of());
        }

        private FakeFileSystem add(
                List<String> components, boolean directory, boolean link, Set<String> aliases) {
            entries.put(List.copyOf(components), new Entry(directory, link, Set.copyOf(aliases)));
            return this;
        }

        @Override
        public List<Path> list(Path directory) throws IOException {
            List<String> parent = components(directory);
            Entry parentEntry = require(parent, directory);
            if (!parentEntry.directory()) {
                throw new IOException("not a directory");
            }
            var children = new ArrayList<Path>();
            for (List<String> candidate : entries.keySet()) {
                if (candidate.size() == parent.size() + 1
                        && candidate.subList(0, parent.size()).equals(parent)) {
                    children.add(directory.resolve(candidate.getLast()));
                }
            }
            return List.copyOf(children);
        }

        @Override
        public MacOsExactSpellingResolver.PathClassification classify(Path path) throws IOException {
            Entry entry = require(components(path), path);
            return new MacOsExactSpellingResolver.PathClassification(
                    entry.directory(), entry.symbolicLink());
        }

        @Override
        public boolean isSameFile(Path first, Path second) throws IOException {
            List<String> firstComponents = components(first);
            List<String> secondComponents = components(second);
            Entry secondEntry = require(secondComponents, second);
            if (firstComponents.size() != secondComponents.size()
                    || !firstComponents.subList(0, firstComponents.size() - 1)
                            .equals(secondComponents.subList(0, secondComponents.size() - 1))) {
                return false;
            }
            String requestedName = firstComponents.getLast();
            return requestedName.equals(secondComponents.getLast())
                    || secondEntry.aliases().contains(requestedName);
        }

        private Entry require(List<String> components, Path path) throws NoSuchFileException {
            Entry entry = entries.get(components);
            if (entry == null) {
                throw new NoSuchFileException(path.toString());
            }
            return entry;
        }

        private static List<String> components(Path path) {
            var values = new ArrayList<String>();
            for (Path component : path) {
                values.add(component.toString());
            }
            return List.copyOf(values);
        }

        private record Entry(boolean directory, boolean symbolicLink, Set<String> aliases) {
            private Entry {
                aliases = new HashSet<>(aliases);
            }
        }
    }
}
