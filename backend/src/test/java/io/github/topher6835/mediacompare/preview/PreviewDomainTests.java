package io.github.topher6835.mediacompare.preview;

import static io.github.topher6835.mediacompare.preview.PreviewTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;

import io.github.topher6835.mediacompare.catalog.FileEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreviewDomainTests {
    @Test
    void locksVersionedEncodingToAnIndependentlyCalculatedFixture() {
        String key = PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL, definition());
        assertEquals("bc60378f1d5d747c1e0bd478fcf25079361c83ce625a2525fb53b5a8e7da7994", key);
        assertEquals(key, PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL, definition()));
        assertTrue(key.matches("[0-9a-f]{64}"));
    }

    @Test
    void everyIdentityFieldChangesTheKey() {
        var base = evidence();
        var definition = definition();
        String original = PreviewAssetKey.compute(base, PreviewKind.SMALL_THUMBNAIL, definition);
        for (var changed : List.of(
                new PreviewSourceEvidence(8, 11, 3, 42, 1700000000, 123456789),
                new PreviewSourceEvidence(7, 12, 3, 42, 1700000000, 123456789),
                new PreviewSourceEvidence(7, 11, 4, 42, 1700000000, 123456789),
                new PreviewSourceEvidence(7, 11, 3, 43, 1700000000, 123456789),
                new PreviewSourceEvidence(7, 11, 3, 42, 1700000001, 123456789),
                new PreviewSourceEvidence(7, 11, 3, 42, 1700000000, 123456790))) {
            assertNotEquals(original, PreviewAssetKey.compute(changed, PreviewKind.SMALL_THUMBNAIL, definition));
        }
        assertNotEquals(original, PreviewAssetKey.compute(base, PreviewKind.MEDIUM_PREVIEW, definition));
        for (var changed : List.of(
                new PreviewDefinition("other.preview", "1", 1, "config-hash", "{}"),
                new PreviewDefinition("test.preview", "2", 1, "config-hash", "{}"),
                new PreviewDefinition("test.preview", "1", 2, "config-hash", "{}"),
                new PreviewDefinition("test.preview", "1", 1, "other-hash", "{}"))) {
            assertNotEquals(original, PreviewAssetKey.compute(base, PreviewKind.SMALL_THUMBNAIL, changed));
        }
    }

    @Test
    void localeJsonOrderingAndDelimiterLikeTextCannotAlterOrConfuseIdentity() {
        Locale original = Locale.getDefault();
        try {
            String expected = PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL, definition());
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(expected, PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL, definition()));
            var reordered = new PreviewDefinition("test.preview", "1", 1, "config-hash", "{\"b\":2,\"a\":1}");
            var otherOrder = new PreviewDefinition("test.preview", "1", 1, "config-hash", "{\"a\":1,\"b\":2}");
            assertEquals(expected, PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL, reordered));
            assertEquals(expected, PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL, otherOrder));
            assertNotEquals(PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL,
                            new PreviewDefinition("a|b", "c", 1, "hash", "{}")),
                    PreviewAssetKey.compute(evidence(), PreviewKind.SMALL_THUMBNAIL,
                            new PreviewDefinition("a", "b|c", 1, "hash", "{}")));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void evidenceRequiresValidIdsRevisionSizeAndCompleteTimeButAllowsPreEpochTimes() {
        for (var values : List.of(new long[] {0,11,3,42}, new long[] {7,0,3,42},
                new long[] {7,11,-1,42}, new long[] {7,11,3,-1})) {
            assertThrows(IllegalArgumentException.class, () -> new PreviewSourceEvidence(
                    values[0], values[1], values[2], values[3], 1, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new PreviewSourceEvidence(7,11,3,42,1,-1));
        assertThrows(IllegalArgumentException.class, () -> new PreviewSourceEvidence(7,11,3,42,1,1_000_000_000));
        assertDoesNotThrow(() -> new PreviewSourceEvidence(7,11,0,0,-1,999_999_999));
        for (FileEntry entry : List.of(entry(null, 11L, 1L, 0), entry(7L, null, 1L, 0),
                entry(7L, 11L, null, 0), entry(7L, 11L, 1L, null))) {
            assertThrows(IllegalArgumentException.class, () -> PreviewSourceEvidence.from(entry));
        }
        assertEquals(evidence(), PreviewSourceEvidence.from(entry(7L, 11L, 1700000000L, 123456789)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid", "[]", "null", "42", "{} {}", "{\"a\":1,\"a\":2}"})
    void rejectsInvalidConfigurationJson(String json) {
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g", "1", 1, "hash", json));
    }

    @Test
    void validatesProvenanceUnicodeAndUtf8JsonBound() {
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition(null,"1",1,"hash","{}"));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition(" ","1",1,"hash","{}"));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g","",1,"hash","{}"));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g","1",0,"hash","{}"));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g","1",1," ","{}"));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g\uD800","1",1,"hash","{}"));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g","1",1,"hash","{\"x\":\"\uD800\"}"));
        String exact = "{\"x\":\"" + "x".repeat(PreviewDefinition.MAX_JSON_UTF8_BYTES - 8) + "\"}";
        assertDoesNotThrow(() -> new PreviewDefinition("g","1",1,"hash",exact));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g","1",1,"hash",exact + " "));
        assertThrows(IllegalArgumentException.class, () -> new PreviewDefinition("g","1",1,"hash",
                "{\"x\":\"" + "é".repeat(65536) + "\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ABC", "../asset", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaag"})
    void rejectsMalformedKeys(String key) {
        assertThrows(IllegalArgumentException.class, () -> PreviewAssetKey.requireValid(key));
    }

    @Test
    void checksSuccessfulAssetMetadataKeyAndLayout() {
        PreviewAsset valid = asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 10);
        assertThrows(IllegalArgumentException.class, () -> new PreviewAsset(null, "a".repeat(64), valid.evidence(),
                valid.kind(), valid.definition(), valid.relativePath(), "image/png", 120, 80, 10, 1));
        for (var values : List.of(new long[] {0,80,10,1}, new long[] {120,0,10,1},
                new long[] {120,80,0,1}, new long[] {120,80,10,-1})) {
            assertThrows(IllegalArgumentException.class, () -> new PreviewAsset(null, valid.assetKey(), valid.evidence(),
                    valid.kind(), valid.definition(), valid.relativePath(), "image/png", (int) values[0],
                    (int) values[1], values[2], values[3]));
        }
        assertThrows(IllegalArgumentException.class, () -> new PreviewAsset(null, valid.assetKey(), valid.evidence(),
                valid.kind(), valid.definition(), "other.png", "image/png",120,80,10,1));
        for (String mediaType : List.of("", "bad mime type", "image/*")) {
            assertThrows(IllegalArgumentException.class, () -> new PreviewAsset(null, valid.assetKey(), valid.evidence(),
                    valid.kind(), valid.definition(), valid.relativePath(), mediaType,120,80,10,1));
        }
    }

    private static FileEntry entry(Long id, Long contentId, Long second, Integer nano) {
        return new FileEntry(id, "UNRESOLVED", null, null, null, contentId, 42, second, nano, "png", 3, 1, 2);
    }
}
