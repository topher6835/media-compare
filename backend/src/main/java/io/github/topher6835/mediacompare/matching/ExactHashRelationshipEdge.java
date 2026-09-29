package io.github.topher6835.mediacompare.matching;

/** One transient undirected SHA-equality connection, with no durable row identity. */
public record ExactHashRelationshipEdge(long contentRecordAId, long contentRecordBId)
        implements MediaRelationshipEdge {

    public ExactHashRelationshipEdge {
        if (contentRecordAId <= 0 || contentRecordBId <= 0 || contentRecordAId == contentRecordBId) {
            throw new IllegalArgumentException("Exact edges require positive distinct ContentRecord IDs");
        }
        if (contentRecordAId > contentRecordBId) {
            long originalA = contentRecordAId;
            contentRecordAId = contentRecordBId;
            contentRecordBId = originalA;
        }
    }

    @Override
    public MediaRelationshipType relationshipType() {
        return MediaRelationshipType.EXACT;
    }
}
