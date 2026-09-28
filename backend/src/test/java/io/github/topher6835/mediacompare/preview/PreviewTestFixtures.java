package io.github.topher6835.mediacompare.preview;

import org.springframework.jdbc.core.JdbcTemplate;

public final class PreviewTestFixtures {
    private PreviewTestFixtures() {
    }

    public static PreviewDefinition definition() {
        return new PreviewDefinition("test.preview", "1", 1, "config-hash", " {\"label\":\"café\"} ");
    }

    public static PreviewSourceEvidence evidence() {
        return new PreviewSourceEvidence(7, 11, 3, 42, 1_700_000_000, 123_456_789);
    }

    public static PreviewAsset asset(PreviewSourceEvidence evidence, PreviewKind kind,
            PreviewDefinition definition, long byteSize) {
        String key = PreviewAssetKey.compute(evidence, kind, definition);
        return new PreviewAsset(null, key, evidence, kind, definition,
                PreviewCacheLayout.relativePath(kind, key, "png"), "image/png", 120, 80, byteSize, 12345);
    }

    public static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (11, 42, 1), (12, 42, 1)");
        jdbc.update("""
                INSERT INTO file_entry (id, location_identity_status, current_content_id, size_bytes,
                    observation_revision, modified_time_epoch_second, modified_time_nano, first_seen_at_ms, last_seen_at_ms)
                VALUES (7, 'UNRESOLVED', 11, 42, 3, 1700000000, 123456789, 1, 2)
                """);
    }
}
