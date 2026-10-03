package io.github.topher6835.mediacompare.filesystem;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WindowsExfatEvidenceCodecTests {
    private final WindowsExfatEvidenceCodec codec = new WindowsExfatEvidenceCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void deterministicContextAndSourceRoundTripsWithoutRuntimeOrPhysicalIdentityClaims() {
        var context = contextEvidence();
        var source = sourceEvidence();
        assertEquals(context, codec.decodeContext(codec.encode(context)));
        assertEquals(source, codec.decodeSource(codec.encode(source)));
        assertEquals(codec.encode(context), codec.encode(codec.decodeContext(codec.encode(context))));
        assertEquals(codec.encode(source), new WindowsExfatEvidenceCodec().encode(codec.decodeSource(codec.encode(source))));
        assertEquals(1, UUID.fromString(GUID.substring(11, GUID.length() - 2)).version());
        for (String document : List.of(codec.encode(context), codec.encode(source))) {
            assertFalse(document.contains("\"windowId\":"));
            assertFalse(document.contains("\"liveAuthorityAvailable\":"));
            assertFalse(document.contains("physicalIdentity"));
            assertFalse(document.contains("legacyIndex"));
        }
    }

    @Test void rejectsEveryMissingFieldUnknownRuntimeFieldsDuplicatesNullsAndCoercions() {
        for (boolean context : List.of(true, false)) {
            String document = context ? codec.encode(contextEvidence()) : codec.encode(sourceEvidence());
            ObjectNode node = (ObjectNode) json.readTree(document);
            for (String field : node.propertyNames()) {
                ObjectNode missing = node.deepCopy(); missing.remove(field);
                reject(context, missing.toString());
                ObjectNode nullField = node.deepCopy(); nullField.putNull(field);
                reject(context, nullField.toString());
                if (node.get(field).isIntegralNumber() || node.get(field).isBoolean()) {
                    ObjectNode coerced = node.deepCopy(); coerced.put(field, node.get(field).asText());
                    reject(context, coerced.toString());
                }
            }
            ObjectNode volume = (ObjectNode) node.get("volume");
            for (String field : volume.propertyNames()) {
                ObjectNode missing = node.deepCopy(); ((ObjectNode) missing.get("volume")).remove(field);
                reject(context, missing.toString());
            }
            for (String field : List.of("unknown", "windowId", "liveAuthorityAvailable", "handle", "processId", "physicalIdentity")) {
                reject(context, document.substring(0, document.length() - 1) + ",\"" + field + "\":1}");
            }
            reject(context, document.replace("\"contextRevision\":1", "\"contextRevision\":1,\"contextRevision\":1"));
            reject(context, document.replace("\"contextRevision\":1", "\"contextRevision\":1.0"));
            for (String malformed : List.of("null", "[]", "{}", document + " {}",
                    document.replace("-v1", "-v2"), document.replace("EXFAT", "NTFS"))) reject(context, malformed);
        }
    }

    @Test void rejectsInvalidIdsRevisionsRoutesVolumesUnicodeAndDocumentBounds() {
        for (boolean context : List.of(true, false)) {
            String document = context ? codec.encode(contextEvidence()) : codec.encode(sourceEvidence());
            for (String id : List.of(CONTEXT.toUpperCase(), "a-b-4ccc-8ddd-e",
                    "aaaaaaaa-bbbb-1ccc-8ddd-eeeeeeeeeeee", "aaaaaaaa-bbbb-4ccc-cddd-eeeeeeeeeeee")) {
                reject(context, document.replace(CONTEXT, id));
            }
            for (String bad : List.of(document.replace("\"contextRevision\":1", "\"contextRevision\":0"),
                    document.replace("\"contextRevision\":1", "\"contextRevision\":9223372036854775808"),
                    document.replace("12345678", "12345XYZ"), document.replace("5b22bd93", "5B22BD93"),
                    document.replace("5b22bd93", "not-guid"), document.replace("\"acceptedAtMs\":10", "\"acceptedAtMs\":-1"),
                    document.replace("\"boundAtMs\":10", "\"boundAtMs\":-1"))) {
                if (!bad.equals(document)) reject(context, bad);
            }
            ObjectNode node = (ObjectNode) json.readTree(document);
            node.put(context ? "anchorLocationKey" : "resolvedRootLocationKey", key(path("C:\\Elsewhere")));
            reject(context, node.toString());
            reject(context, document.replace("EXPLICIT_PREPARE", "\\ud800PREPARE"));
            reject(context, document + "\ud800");
            reject(context, " ".repeat(WindowsExfatEvidenceCodec.MAX_DOCUMENT_UTF8_BYTES) + document);
            reject(context, "\u00e9".repeat(WindowsExfatEvidenceCodec.MAX_DOCUMENT_UTF8_BYTES / 2 + 1));
        }
        reject(false, codec.encode(sourceEvidence()).replace("\"sourceId\":1", "\"sourceId\":0"));
        reject(false, codec.encode(sourceEvidence()).replace("\"directory\":true", "\"directory\":false"));
        reject(false, codec.encode(sourceEvidence()).replace("\"nonLink\":true", "\"nonLink\":false"));
    }

    @Test void rejectsNumericAndBooleanTextCoercionIncludingAnOtherwiseValidEightDigitSerial() {
        for (boolean context : List.of(true, false)) {
            String document = context ? codec.encode(contextEvidence()) : codec.encode(sourceEvidence());
            ObjectNode node = (ObjectNode) json.readTree(document);
            for (String field : node.propertyNames()) {
                if (node.get(field).isTextual()) {
                    ObjectNode changed = node.deepCopy(); changed.put(field, 12345678);
                    reject(context, changed.toString());
                } else if (node.get(field).isBoolean()) {
                    ObjectNode changed = node.deepCopy(); changed.put(field, 1);
                    reject(context, changed.toString());
                }
            }
            for (String field : ((ObjectNode) node.get("volume")).propertyNames()) {
                ObjectNode integer = node.deepCopy(); ((ObjectNode) integer.get("volume")).put(field, 12345678);
                ObjectNode decimal = node.deepCopy(); ((ObjectNode) decimal.get("volume")).put(field, 12345678.0);
                ObjectNode bool = node.deepCopy(); ((ObjectNode) bool.get("volume")).put(field, true);
                reject(context, integer.toString());
                reject(context, decimal.toString());
                reject(context, bool.toString());
            }
        }
    }

    private void reject(boolean context, String document) {
        assertThrows(IllegalArgumentException.class, () -> {
            if (context) codec.decodeContext(document); else codec.decodeSource(document);
        });
    }
}
