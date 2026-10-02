package io.github.topher6835.mediacompare.filesystem;

import java.util.List;
import java.util.UUID;

import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;

/** Database-only receipt fixtures; these never acquire or claim mounted exFAT authority. */
public final class ExfatReceiptTestFixtures {
    public static final String CONTEXT = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    public static final String TOKEN = "11111111-2222-4333-8444-555555555555";
    public static final String BUNDLE = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    public static final LocationPath ROOT = new LocationPath(LocationDialect.WINDOWS_DRIVE, List.of("X"), List.of("photos"));
    public static final LocationPath FILE = ROOT.append("a.jpg");
    private static final LocationPathCodec PATHS = new LocationPathCodec();
    private ExfatReceiptTestFixtures() { }

    public static ExfatObservationReceipt receipt() {
        return receipt(TOKEN, ROOT, List.of(source(1, 1, ROOT)));
    }

    public static ExfatObservationReceipt.SourceEntry source(long id, long membershipId, LocationPath root) {
        // Fixed version/variant bits keep deterministic fixture windows valid version 4 UUIDs.
        return new ExfatObservationReceipt.SourceEntry(id, 1, PATHS.encode(root), key(root),
                new UUID(0x4000L, Long.MIN_VALUE | id).toString(), membershipId, 0, id, 1, "a.jpg");
    }

    public static ExfatObservationReceipt receipt(String token, LocationPath root,
            List<ExfatObservationReceipt.SourceEntry> sources) {
        LocationPath file = root.append("a.jpg");
        var evidence = new ExfatObservationReceipt.ClassificationEvidence(0x20, 42,
                116444736000000000L + 10000000L * 500 + 1234567, true, false, false, false,
                42, 500, 123456700, "exFAT", "\\\\?\\X:\\" + String.join("\\", file.components()),
                "1234abcd", "\\\\?\\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\\", "0000000000000123");
        return new ExfatObservationReceipt(ExfatObservationReceipt.VERSION, token, 0,
                PATHS.encode(file), key(file), CONTEXT, 1, 42, 42, "a".repeat(64), 500, 123456700,
                evidence, evidence, 1000, 1001, BUNDLE, 1, 1, sources);
    }

    public static FileEntry entry(long id, ExfatObservationReceipt receipt) {
        return new FileEntry(id, "RESOLVED", receipt.contextId(), receipt.locationPath(), receipt.locationKey(),
                null, receipt.sizeBytes(), receipt.modifiedTimeEpochSecond(), receipt.modifiedTimeNano(), "jpg",
                receipt.fileObservationRevision(), 1000, 1001, receipt.occurrenceToken(),
                new ExfatObservationReceiptCodec().encode(receipt));
    }

    public static String key(LocationPath path) { return LocationKeyCodec.encode(path).value(); }
}
