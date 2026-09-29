package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class MediaRelationshipDefinitionTests {

    @Test
    void preservesExactDefinitionAndMatchesRegardlessOfResultMetadata() {
        String json = " {\"threshold\":0.8,\"label\":\"café 😀\"} ";
        MediaRelationshipDefinition definition = definition(MediaRelationshipType.CROP,
                "test.crop", "1", 1, "hash", json);
        assertEquals(json, definition.configurationJson());
        assertEquals(definition, definition(MediaRelationshipType.CROP, "test.crop", "1", 1, "hash", json));
        assertTrue(definition.matches(edge(definition)));
        assertTrue(definition.matches(new MediaRelationship(99L, 40, 30, MediaRelationshipType.CROP,
                MediaRelationshipDirection.A_TO_B, 0.7, "{\"crop\":true}", "test.crop", "1", 1,
                "hash", json, 999)));
    }

    @Test
    void requiresTypeAndCompleteNonblankProvenance() {
        assertThrows(NullPointerException.class, () -> definition(null, "test.crop", "1", 1, "hash", "{}"));
        for (String missing : Arrays.asList(null, "", " \t\n")) {
            assertThrows(IllegalArgumentException.class, () -> definition(MediaRelationshipType.CROP,
                    missing, "1", 1, "hash", "{}"));
            assertThrows(IllegalArgumentException.class, () -> definition(MediaRelationshipType.CROP,
                    "test.crop", missing, 1, "hash", "{}"));
            assertThrows(IllegalArgumentException.class, () -> definition(MediaRelationshipType.CROP,
                    "test.crop", "1", 1, missing, "{}"));
        }
        for (long invalid : List.of(0L, -1L)) {
            assertThrows(IllegalArgumentException.class, () -> definition(MediaRelationshipType.CROP,
                    "test.crop", "1", invalid, "hash", "{}"));
        }
    }

    @Test
    void rejectsInvalidJsonWithTheSameRulesAsRelationshipArtifacts() {
        for (String invalid : Arrays.asList(null, "", " ", "not-json", "[]", "null", "123", "true", "{",
                "{} {}", "{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{\"x\":\"\uD800\"}", "{\"x\":\"\uDC00\"}")) {
            IllegalArgumentException definitionError = assertThrows(IllegalArgumentException.class,
                    () -> jsonDefinition(invalid));
            IllegalArgumentException relationshipError = assertThrows(IllegalArgumentException.class,
                    () -> new MediaRelationship(null, 1, 2, MediaRelationshipType.CROP,
                            MediaRelationshipDirection.UNDIRECTED, null, "{}", "test.crop", "1", 1,
                            "hash", invalid, 1));
            assertEquals(relationshipError.getMessage(), definitionError.getMessage());
        }
    }

    @Test
    void boundsConfigurationByUtf8BytesWithoutReformatting() {
        String atLimit = "{\"x\":\"" + "é".repeat((MediaRelationship.MAX_JSON_UTF8_BYTES - 8) / 2) + "\"}";
        assertEquals(atLimit, jsonDefinition(atLimit).configurationJson());
        assertThrows(IllegalArgumentException.class, () -> jsonDefinition(atLimit + " "));
        assertThrows(IllegalArgumentException.class,
                () -> jsonDefinition("{\"x\":\"" + "a".repeat(MediaRelationship.MAX_JSON_UTF8_BYTES) + "\"}"));
    }

    @Test
    void everyDefinitionFieldMustMatchIncludingJsonWithTheSameHash() {
        MediaRelationshipDefinition selected = jsonDefinition("{\"x\":1}");
        for (MediaRelationshipDefinition other : List.of(
                definition(MediaRelationshipType.RESIZED, "test.crop", "1", 1, "hash", "{\"x\":1}"),
                definition(MediaRelationshipType.CROP, "other.crop", "1", 1, "hash", "{\"x\":1}"),
                definition(MediaRelationshipType.CROP, "test.crop", "2", 1, "hash", "{\"x\":1}"),
                definition(MediaRelationshipType.CROP, "test.crop", "1", 2, "hash", "{\"x\":1}"),
                definition(MediaRelationshipType.CROP, "test.crop", "1", 1, "other-hash", "{\"x\":1}"),
                jsonDefinition("{\"x\":2}"), jsonDefinition(" {\"x\":1} "))) {
            assertFalse(selected.matches(edge(other)), other.toString());
        }
    }

    private static MediaRelationshipDefinition jsonDefinition(String json) {
        return definition(MediaRelationshipType.CROP, "test.crop", "1", 1, "hash", json);
    }

    private static MediaRelationshipDefinition definition(MediaRelationshipType type,
            String matcher, String version, long configVersion, String hash, String json) {
        return new MediaRelationshipDefinition(type, matcher, version, configVersion, hash, json);
    }

    private static MediaRelationship edge(MediaRelationshipDefinition definition) {
        return new MediaRelationship(null, 1, 2, definition.relationshipType(),
                MediaRelationshipDirection.UNDIRECTED, null, "{}", definition.matcherId(),
                definition.matcherVersion(), definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson(), 1);
    }
}
