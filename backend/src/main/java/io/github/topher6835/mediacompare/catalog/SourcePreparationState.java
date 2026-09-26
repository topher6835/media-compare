package io.github.topher6835.mediacompare.catalog;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;

/** Product state derived from the current durable Source binding shape. */
public enum SourcePreparationState {
    PREPARATION_REQUIRED,
    READY,
    REBIND_REQUIRED;

    public static SourcePreparationState from(Source source) {
        Objects.requireNonNull(source, "Source");
        if (source.boundLocationContextId() != null) {
            SourceBindingAuthority.requireCurrentBound(source);
            return READY;
        }
        if (source.bindingEvidenceJson() != null) {
            throw new IllegalStateException("Unbound Source has binding evidence");
        }
        if (source.rootPathDialect() == null) {
            if (!Objects.equals(source.rootPathKey(), source.rootPath())) {
                throw new IllegalStateException("Unprepared Source root and key disagree");
            }
            return PREPARATION_REQUIRED;
        }
        try {
            if (!LocationDialect.UNIX.persistedName().equals(source.rootPathDialect())) {
                throw new IllegalArgumentException("Unsupported structured Source dialect");
            }
            LocationPath root = LocationPathParser.parse(LocationDialect.UNIX, source.rootPath());
            if (!LocationKeyCodec.matches(root, LocationKey.parse(source.rootPathKey()))) {
                throw new IllegalArgumentException("Structured Source root and key disagree");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid structured-unbound Source", exception);
        }
        return REBIND_REQUIRED;
    }
}
