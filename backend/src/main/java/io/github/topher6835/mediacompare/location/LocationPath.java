package io.github.topher6835.mediacompare.location;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A lossless, structured address whose equality is independent of the host OS. */
public record LocationPath(LocationDialect dialect, List<String> rootFields, List<String> components) {

    public LocationPath {
        Objects.requireNonNull(dialect, "dialect");
        rootFields = List.copyOf(Objects.requireNonNull(rootFields, "rootFields"));
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        LocationPathValidation.validate(dialect, rootFields, components);
    }

    /** Returns true for this path itself and for structurally contained descendants. */
    public boolean contains(LocationPath candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (dialect != candidate.dialect || !rootFields.equals(candidate.rootFields)
                || components.size() > candidate.components.size()) {
            return false;
        }
        for (int index = 0; index < components.size(); index++) {
            if (!components.get(index).equals(candidate.components.get(index))) {
                return false;
            }
        }
        return true;
    }

    public LocationPath append(String component) {
        var appended = new ArrayList<>(components);
        appended.add(component);
        return new LocationPath(dialect, rootFields, appended);
    }
}
