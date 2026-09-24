package io.github.topher6835.mediacompare.location;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Strict deterministic JSON codec for Source binding provenance v1. */
public final class SourceBindingEvidenceCodec {
    public static final int MAX_DOCUMENT_UTF8_BYTES = EvidenceJsonSupport.MAX_DOCUMENT_UTF8_BYTES;
    private static final Set<String> FIELDS = Set.of("version", "sourceId", "macOsApfsSourceRootEvidence");

    private final EvidenceJsonSupport json = new EvidenceJsonSupport();
    private final MacOsApfsSourceRootEvidenceCodec rootCodec = new MacOsApfsSourceRootEvidenceCodec();

    public String encode(SourceBindingEvidence evidence) {
        Map<String, Object> fields = EvidenceJsonSupport.object();
        fields.put("version", evidence.version());
        fields.put("sourceId", evidence.sourceId());
        fields.put("macOsApfsSourceRootEvidence",
                json.parse(rootCodec.encode(evidence.macOsApfsSourceRootEvidence())));
        return json.write(fields);
    }

    public SourceBindingEvidence decode(String storedJson) {
        JsonNode root = json.parse(storedJson);
        EvidenceJsonSupport.requireExactFields(root, FIELDS);
        JsonNode sourceRoot = EvidenceJsonSupport.requiredField(root, "macOsApfsSourceRootEvidence");
        EvidenceJsonSupport.requireObject(sourceRoot, "macOsApfsSourceRootEvidence");
        return new SourceBindingEvidence(
                EvidenceJsonSupport.requiredInt(root, "version"),
                EvidenceJsonSupport.requiredLong(root, "sourceId"),
                rootCodec.decode(sourceRoot.toString()));
    }
}
