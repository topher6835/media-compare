package io.github.topher6835.mediacompare.filesystem;

import static io.github.topher6835.mediacompare.filesystem.ExfatReceiptTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.scan.IndexingRunService;

class ExfatObservationReceiptCodecTests {
    private final ExfatObservationReceiptCodec codec = new ExfatObservationReceiptCodec();

    @Test
    void roundTripIsDeterministicAndKeepsCompleteProvenance() {
        var value = receipt(TOKEN, ROOT, List.of(source(1, 7, ROOT), source(2, 8, ROOT)));
        String document = codec.encode(value);
        assertEquals(value, codec.decode(document));
        assertEquals(document, codec.encode(codec.decode(document)));
        assertEquals(document, new ExfatObservationReceiptCodec().encode(value));
        assertTrue(document.contains("contradictionOnlyLegacyIndex"));
        assertEquals(8, codec.decode(document).sources().get(1).membershipId());
        // The qualified OS volume GUID is version 1, separate from application UUIDs.
        assertEquals("\\\\?\\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\\", value.before().volumeGuid());
        assertThrows(UnsupportedOperationException.class, () -> value.sources().clear());
        assertFalse(WindowsExfatSupport.PRODUCTION.available());
    }

    @Test
    void rejectsWrongShapesUnknownDuplicateMissingFieldsAndScalarCoercion() {
        String valid = codec.encode(receipt());
        for (String bad : List.of("null", "[]", "{}", valid + " {}",
                valid.replace("windows-exfat-observation-v1", "windows-exfat-observation-v2"),
                valid.replace(CONTEXT, CONTEXT.toUpperCase(Locale.ROOT)),
                valid.replace(CONTEXT, "not-a-context"),
                valid.replace("\"byteCount\":42", "\"byteCount\":41"),
                valid.replace("\"byteCount\":42", "\"byteCount\":\"42\""),
                valid.replace("\"byteCount\":42", "\"byteCount\":42.0"),
                valid.replace("\"byteCount\":42", "\"byteCount\":null"),
                valid.replace("\"byteCount\":42,", ""),
                valid.replace("\"byteCount\":42", "\"byteCount\":42,\"byteCount\":42"),
                valid.replace("\"scanJobId\":1", "\"scanJobId\":0"),
                valid.replace("\"sourceRevision\":1", "\"sourceRevision\":-1"),
                valid.replace("\"generation\":1", "\"generation\":0"),
                valid.replace("\"fileObservationRevision\":0", "\"fileObservationRevision\":9223372036854775808"),
                valid.replace("\"observationFinishedAtMs\":1001", "\"observationFinishedAtMs\":999"),
                valid.replace("\"modifiedTimeNano\":123456700", "\"modifiedTimeNano\":1000000000"),
                valid.replace("\"sha256\":\"" + "a".repeat(64), "\"sha256\":\"" + "A".repeat(64)),
                valid.substring(0, valid.length() - 1) + ",\"unknown\":1}",
                valid.replace("\"sourceId\":1", "\"unknownSourceField\":1,\"sourceId\":1"),
                valid.replace("\"nativeAttributes\":32", "\"nativeAttributes\":32,\"handle\":123"))) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bad), bad);
        }
    }

    @Test
    void rejectsEveryUnsupportedUuidVersionInEveryApplicationIdField() {
        for (int version = 0; version <= 15; version++) {
            if (version == 4) continue;
            rejectUuidForEveryApplicationField("aaaaaaaa-bbbb-" + Integer.toHexString(version)
                    + "ccc-8ddd-eeeeeeeeeeee");
        }
    }

    @Test
    void rejectsNonIetfUuidVariantsInEveryApplicationIdField() {
        for (int variant = 0; variant <= 15; variant++) {
            if (variant >= 8 && variant <= 11) continue;
            rejectUuidForEveryApplicationField("aaaaaaaa-bbbb-4ccc-" + Integer.toHexString(variant)
                    + "ddd-eeeeeeeeeeee");
        }
    }

    @Test
    void rejectsUppercaseAndOtherwiseNoncanonicalUuidTextInEveryApplicationIdField() {
        String shortened = "a-b-4ccc-8ddd-e";
        assertEquals("0000000a-000b-4ccc-8ddd-00000000000e", UUID.fromString(shortened).toString());
        for (String bad : List.of(CONTEXT.toUpperCase(Locale.ROOT), shortened,
                " " + CONTEXT, CONTEXT + " ", CONTEXT.replace("-", ""))) {
            rejectUuidForEveryApplicationField(bad);
        }
    }

    private void rejectUuidForEveryApplicationField(String bad) {
        var value = receipt();
        String valid = codec.encode(value);
        var fields = Map.of("occurrenceToken", value.occurrenceToken(), "contextId", value.contextId(),
                "bundleUuid", value.bundleUuid(), "windowUuid", value.sources().getFirst().windowUuid());
        for (var field : fields.entrySet()) {
            String document = valid.replace("\"" + field.getKey() + "\":\"" + field.getValue() + "\"",
                    "\"" + field.getKey() + "\":\"" + bad + "\"");
            assertNotEquals(valid, document, field.getKey());
            assertThrows(IllegalArgumentException.class, () -> codec.decode(document), field.getKey() + ": " + bad);
        }
    }

    @Test
    void rejectsIncoherentAndUnsafeClassificationAndRoutes() {
        String valid = codec.encode(receipt());
        for (String bad : List.of(
                valid.replace("\"nioRegularFile\":true", "\"nioRegularFile\":false"),
                valid.replace("\"nioSymbolicLink\":false", "\"nioSymbolicLink\":true"),
                valid.replace("\"nativeAttributes\":32", "\"nativeAttributes\":1056"),
                valid.replace("\"nativeAttributes\":32", "\"nativeAttributes\":4294967296"),
                valid.replace("\"nativeSizeBytes\":42", "\"nativeSizeBytes\":41"),
                valid.replace("\"nioModifiedTimeNano\":123456700", "\"nioModifiedTimeNano\":123456701"),
                valid.replace("\"nioFileSystemType\":\"exFAT\"", "\"nioFileSystemType\":\"NTFS\""),
                valid.replace("1234abcd", "1234ABCD"),
                valid.replace("Volume{", "volume{"),
                valid.replace("a.jpg", "b.jpg"),
                valid.replace("lk1:", "lk2:"),
                valid.replace("\"relativeRoute\":\"a.jpg\"", "\"relativeRoute\":\"../a.jpg\""),
                valid.replace("0000000000000123", "legacy-identity"),
                valid.replace("\"relativeRoute\":\"a.jpg\"", "\"relativeRoute\":\"\\ud800\""))) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(valid + "\ud800"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(valid + "\udc00"));
    }

    @Test
    void rejectsUnorderedDuplicateOrExcessiveSourceProvenance() {
        assertThrows(IllegalArgumentException.class, () -> receipt(TOKEN, ROOT, List.of()));
        assertThrows(IllegalArgumentException.class, () -> receipt(TOKEN, ROOT,
                List.of(source(2, 2, ROOT), source(1, 1, ROOT))));
        assertThrows(IllegalArgumentException.class, () -> receipt(TOKEN, ROOT,
                List.of(source(1, 1, ROOT), source(1, 2, ROOT))));
        assertThrows(IllegalArgumentException.class, () -> receipt(TOKEN, ROOT,
                List.of(source(1, 1, ROOT), source(2, 1, ROOT))));
        var routes = sources(ROOT, IndexingRunService.MAX_SOURCE_IDS);
        assertEquals(1000, codec.decode(codec.encode(receipt(TOKEN, ROOT, routes))).sources().size());
        var excessive = sources(ROOT, IndexingRunService.MAX_SOURCE_IDS + 1);
        assertThrows(IllegalArgumentException.class, () -> receipt(TOKEN, ROOT, excessive));
    }

    @Test
    void enforcesExactUtf8DocumentCapOnEncodingAndDecodingWithoutTruncation() {
        String valid = codec.encode(receipt());
        int bytes = valid.getBytes(StandardCharsets.UTF_8).length;
        String atLimit = valid + " ".repeat(ExfatObservationReceiptCodec.MAX_DOCUMENT_UTF8_BYTES - bytes);
        assertEquals(receipt(), codec.decode(atLimit));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(atLimit + " "));
        String multibyte = valid + "é".repeat((ExfatObservationReceiptCodec.MAX_DOCUMENT_UTF8_BYTES - bytes) / 2 + 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(multibyte));
        var longRoot = new LocationPath(LocationDialect.WINDOWS_DRIVE, List.of("X"), List.of("x".repeat(1024)));
        var oversized = receipt(TOKEN, longRoot, sources(longRoot, IndexingRunService.MAX_SOURCE_IDS));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(oversized));
    }

    private static List<ExfatObservationReceipt.SourceEntry> sources(LocationPath root, int count) {
        var values = new ArrayList<ExfatObservationReceipt.SourceEntry>();
        for (int id = 1; id <= count; id++) values.add(source(id, id, root));
        return values;
    }
}
