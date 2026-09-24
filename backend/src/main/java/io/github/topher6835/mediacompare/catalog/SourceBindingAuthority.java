package io.github.topher6835.mediacompare.catalog;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;

/** Checks that a currently bound Source row owns its persisted binding evidence. */
public final class SourceBindingAuthority {
    private static final SourceBindingEvidenceCodec CODEC = new SourceBindingEvidenceCodec();

    private SourceBindingAuthority() {
    }

    public static SourceBindingEvidence requireCurrentBound(Source source) {
        Objects.requireNonNull(source, "Source is required");
        if (source.boundLocationContextId() == null) {
            throw new IllegalArgumentException("Source is not bound");
        }
        final SourceBindingEvidence binding;
        final LocationPath root;
        try {
            if (!LocationDialect.UNIX.persistedName().equals(source.rootPathDialect())) {
                throw new IllegalArgumentException("Unsupported bound Source dialect");
            }
            root = LocationPathParser.parse(LocationDialect.UNIX, source.rootPath());
            LocationKey key = LocationKey.parse(source.rootPathKey());
            if (!LocationKeyCodec.matches(root, key)) {
                throw new IllegalArgumentException("Bound Source root path and key disagree");
            }
            binding = CODEC.decode(source.bindingEvidenceJson());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid current binding evidence for Source " + source.id(), exception);
        }
        if (source.id() == null || binding.sourceId() != source.id()
                || binding.macOsApfsSourceRootEvidence().sourceLocationRevision() != source.locationRevision()
                || !binding.macOsApfsSourceRootEvidence().locationContextId().equals(
                        source.boundLocationContextId())
                || !binding.macOsApfsSourceRootEvidence().rootLocationPath().equals(root)
                || !binding.macOsApfsSourceRootEvidence().rootLocationKey().value().equals(source.rootPathKey())) {
            throw new IllegalStateException("Binding evidence does not match current Source " + source.id());
        }
        return binding;
    }
}
