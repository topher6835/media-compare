package io.github.topher6835.mediacompare.scan.authority;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Host-reported coverage; COMPLETE must mean every path in the claimed scope was examined. */
public record TraversalCompletion(LocationPath startScope, LocationPath endScope, Issue issue) {
    public TraversalCompletion {
        Objects.requireNonNull(startScope, "start scope");
        Objects.requireNonNull(endScope, "end scope");
        Objects.requireNonNull(issue, "issue");
    }

    public enum Issue {
        COMPLETE, ERROR, CANCELLED, INACCESSIBLE_SUBTREE,
        UNSUPPORTED_CHILD_STORAGE, UNCERTAIN_CHILD_STORAGE, SYMBOLIC_LINK_AMBIGUITY
    }
}
