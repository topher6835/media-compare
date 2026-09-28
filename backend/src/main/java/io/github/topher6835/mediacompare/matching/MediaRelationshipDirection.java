package io.github.topher6835.mediacompare.matching;

/** Direction relative to the relationship's stored A and B endpoints. */
public enum MediaRelationshipDirection {
    UNDIRECTED,
    A_TO_B,
    B_TO_A;

    public MediaRelationshipDirection reversed() {
        return switch (this) {
            case UNDIRECTED -> UNDIRECTED;
            case A_TO_B -> B_TO_A;
            case B_TO_A -> A_TO_B;
        };
    }
}
