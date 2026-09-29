package io.github.topher6835.mediacompare.matching;

/** ContentRecord connectivity shared by durable relationships and transient projections. */
public interface MediaRelationshipEdge {
    long contentRecordAId();
    long contentRecordBId();
    MediaRelationshipType relationshipType();
}
