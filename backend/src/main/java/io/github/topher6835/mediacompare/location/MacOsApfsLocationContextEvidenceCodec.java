package io.github.topher6835.mediacompare.location;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Strict deterministic JSON codec for macOS local-APFS LocationContext evidence v1. */
public final class MacOsApfsLocationContextEvidenceCodec {
    public static final int MAX_DOCUMENT_UTF8_BYTES = EvidenceJsonSupport.MAX_DOCUMENT_UTF8_BYTES;

    private static final Set<String> FIELDS = Set.of(
            "version", "profile", "profileVersion", "anchorLocationPath", "anchorLocationKey",
            "fileSystemType", "volumeUuid", "anchorInode", "directory", "symbolicLink",
            "acceptedAtMs", "diagnostics");
    private static final Set<String> DIAGNOSTIC_FIELDS = Set.of(
            "unixDevice", "fileStoreName", "providerClass");

    private final EvidenceJsonSupport json = new EvidenceJsonSupport();

    public String encode(MacOsApfsLocationContextEvidence evidence) {
        Map<String, Object> fields = EvidenceJsonSupport.object();
        fields.put("version", evidence.version());
        fields.put("profile", evidence.profile());
        fields.put("profileVersion", evidence.profileVersion());
        fields.put("anchorLocationPath", json.encodedPath(evidence.anchorLocationPath()));
        fields.put("anchorLocationKey", evidence.anchorLocationKey().value());
        fields.put("fileSystemType", evidence.fileSystemType());
        fields.put("volumeUuid", evidence.volumeUuid());
        fields.put("anchorInode", evidence.anchorInode());
        fields.put("directory", evidence.directory());
        fields.put("symbolicLink", evidence.symbolicLink());
        fields.put("acceptedAtMs", evidence.acceptedAtMs());
        fields.put("diagnostics", diagnostics(evidence.diagnostics()));
        return json.write(fields);
    }

    public MacOsApfsLocationContextEvidence decode(String storedJson) {
        JsonNode root = json.parse(storedJson);
        EvidenceJsonSupport.requireExactFields(root, FIELDS);
        LocationPath path = json.requiredPath(root, "anchorLocationPath");
        LocationKey key = LocationKey.parse(
                EvidenceJsonSupport.requiredString(root, "anchorLocationKey"));
        JsonNode diagnostics = EvidenceJsonSupport.requiredField(root, "diagnostics");
        EvidenceJsonSupport.requireObject(diagnostics, "diagnostics");
        EvidenceJsonSupport.requireOnlyFields(diagnostics, DIAGNOSTIC_FIELDS);

        return new MacOsApfsLocationContextEvidence(
                EvidenceJsonSupport.requiredInt(root, "version"),
                EvidenceJsonSupport.requiredString(root, "profile"),
                EvidenceJsonSupport.requiredInt(root, "profileVersion"),
                path,
                key,
                EvidenceJsonSupport.requiredString(root, "fileSystemType"),
                EvidenceJsonSupport.requiredString(root, "volumeUuid"),
                EvidenceJsonSupport.requiredString(root, "anchorInode"),
                EvidenceJsonSupport.requiredBoolean(root, "directory"),
                EvidenceJsonSupport.requiredBoolean(root, "symbolicLink"),
                EvidenceJsonSupport.requiredLong(root, "acceptedAtMs"),
                new MacOsApfsLocationContextEvidence.Diagnostics(
                        EvidenceJsonSupport.optionalString(diagnostics, "unixDevice"),
                        EvidenceJsonSupport.optionalString(diagnostics, "fileStoreName"),
                        EvidenceJsonSupport.optionalString(diagnostics, "providerClass")));
    }

    private static Map<String, Object> diagnostics(MacOsApfsLocationContextEvidence.Diagnostics diagnostics) {
        Map<String, Object> fields = EvidenceJsonSupport.object();
        if (diagnostics.unixDevice() != null) {
            fields.put("unixDevice", diagnostics.unixDevice());
        }
        if (diagnostics.fileStoreName() != null) {
            fields.put("fileStoreName", diagnostics.fileStoreName());
        }
        if (diagnostics.providerClass() != null) {
            fields.put("providerClass", diagnostics.providerClass());
        }
        return fields;
    }
}
