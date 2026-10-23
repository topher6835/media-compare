package io.github.topher6835.mediacompare.scan.authority;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Host-reported coverage; COMPLETE means the requested recursive or direct-file scope was examined. */
public record TraversalCompletion(LocationPath startScope, LocationPath endScope, Issue issue, boolean includeSubfolders) {
    public TraversalCompletion(LocationPath startScope, LocationPath endScope, Issue issue) {
        this(startScope, endScope, issue, true);
    }
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
