package io.github.topher6835.mediacompare.matching;

import static io.github.topher6835.mediacompare.matching.MediaRelationshipValidation.requireConfigurationVersion;
import static io.github.topher6835.mediacompare.matching.MediaRelationshipValidation.requireJsonObject;
import static io.github.topher6835.mediacompare.matching.MediaRelationshipValidation.requireNonBlank;

import java.util.Objects;

/** One durable matcher result between immutable content identities, independent of file locations. */
public record MediaRelationship(
        Long id,
        long contentRecordAId,
        long contentRecordBId,
        MediaRelationshipType relationshipType,
        MediaRelationshipDirection direction,
        Double confidence,
        String evidenceJson,
        String matcherId,
        String matcherVersion,
        long configurationVersion,
        String configurationHash,
        String configurationJson,
        long createdAtMs) {

    public static final int MAX_JSON_UTF8_BYTES = MediaRelationshipValidation.MAX_JSON_UTF8_BYTES;

    /** Input direction refers to the supplied endpoints; swapping them reverses direction. */
    public MediaRelationship {
        if ((id != null && id <= 0) || contentRecordAId <= 0 || contentRecordBId <= 0) {
            throw new IllegalArgumentException("Relationship and ContentRecord IDs must be positive");
        }
        if (contentRecordAId == contentRecordBId) {
            throw new IllegalArgumentException("A relationship requires distinct ContentRecords");
        }
        Objects.requireNonNull(relationshipType, "relationshipType");
        Objects.requireNonNull(direction, "direction");
        if (contentRecordAId > contentRecordBId) {
            long originalA = contentRecordAId;
            contentRecordAId = contentRecordBId;
            contentRecordBId = originalA;
            direction = direction.reversed();
        }
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence must be null or finite within 0.0..1.0");
        }
        requireNonBlank(matcherId, "matcherId");
        requireNonBlank(matcherVersion, "matcherVersion");
        requireConfigurationVersion(configurationVersion);
        requireNonBlank(configurationHash, "configurationHash");
        requireJsonObject(evidenceJson, "evidenceJson");
        requireJsonObject(configurationJson, "configurationJson");
    }
}
