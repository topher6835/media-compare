package io.github.topher6835.mediacompare.location;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Strict deterministic JSON codec for LocationContext acceptance provenance v1. */
public final class LocationContextAcceptanceEvidenceCodec {
    public static final int MAX_DOCUMENT_UTF8_BYTES = EvidenceJsonSupport.MAX_DOCUMENT_UTF8_BYTES;
    private static final Set<String> FIELDS = Set.of(
            "version", "contextId", "contextRevision", "macOsApfsEvidence");

    private final EvidenceJsonSupport json = new EvidenceJsonSupport();
    private final MacOsApfsLocationContextEvidenceCodec apfsCodec = new MacOsApfsLocationContextEvidenceCodec();

    public String encode(LocationContextAcceptanceEvidence evidence) {
        Map<String, Object> fields = EvidenceJsonSupport.object();
        fields.put("version", evidence.version());
        fields.put("contextId", evidence.contextId());
        fields.put("contextRevision", evidence.contextRevision());
        fields.put("macOsApfsEvidence", json.parse(apfsCodec.encode(evidence.macOsApfsEvidence())));
        return json.write(fields);
    }

    public LocationContextAcceptanceEvidence decode(String storedJson) {
        JsonNode root = json.parse(storedJson);
        EvidenceJsonSupport.requireExactFields(root, FIELDS);
        JsonNode apfs = EvidenceJsonSupport.requiredField(root, "macOsApfsEvidence");
        EvidenceJsonSupport.requireObject(apfs, "macOsApfsEvidence");
        return new LocationContextAcceptanceEvidence(
                EvidenceJsonSupport.requiredInt(root, "version"),
                EvidenceJsonSupport.requiredString(root, "contextId"),
                EvidenceJsonSupport.requiredLong(root, "contextRevision"),
                apfsCodec.decode(apfs.toString()));
    }
}
