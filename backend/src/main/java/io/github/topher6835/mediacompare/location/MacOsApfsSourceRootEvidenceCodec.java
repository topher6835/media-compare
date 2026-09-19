package io.github.topher6835.mediacompare.location;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Strict deterministic JSON codec for macOS local-APFS Source-root evidence v1. */
public final class MacOsApfsSourceRootEvidenceCodec {
    public static final int MAX_DOCUMENT_UTF8_BYTES = EvidenceJsonSupport.MAX_DOCUMENT_UTF8_BYTES;

    private static final Set<String> FIELDS = Set.of(
            "version", "profile", "profileVersion", "locationContextId", "locationContextRevision",
            "sourceLocationRevision", "rootLocationPath", "rootLocationKey", "volumeUuid",
            "rootInode", "rootBirthTime", "directory", "symbolicLink", "acceptedAtMs");
    private static final Set<String> BIRTH_TIME_FIELDS = Set.of("epochSecond", "nano");

    private final EvidenceJsonSupport json = new EvidenceJsonSupport();

    public String encode(MacOsApfsSourceRootEvidence evidence) {
        Map<String, Object> birthTime = EvidenceJsonSupport.object();
        birthTime.put("epochSecond", evidence.rootBirthTime().epochSecond());
        birthTime.put("nano", evidence.rootBirthTime().nano());

        Map<String, Object> fields = EvidenceJsonSupport.object();
        fields.put("version", evidence.version());
        fields.put("profile", evidence.profile());
        fields.put("profileVersion", evidence.profileVersion());
        fields.put("locationContextId", evidence.locationContextId());
        fields.put("locationContextRevision", evidence.locationContextRevision());
        fields.put("sourceLocationRevision", evidence.sourceLocationRevision());
        fields.put("rootLocationPath", json.encodedPath(evidence.rootLocationPath()));
        fields.put("rootLocationKey", evidence.rootLocationKey().value());
        fields.put("volumeUuid", evidence.volumeUuid());
        fields.put("rootInode", evidence.rootInode());
        fields.put("rootBirthTime", birthTime);
        fields.put("directory", evidence.directory());
        fields.put("symbolicLink", evidence.symbolicLink());
        fields.put("acceptedAtMs", evidence.acceptedAtMs());
        return json.write(fields);
    }

    public MacOsApfsSourceRootEvidence decode(String storedJson) {
        JsonNode root = json.parse(storedJson);
        EvidenceJsonSupport.requireExactFields(root, FIELDS);
        LocationPath path = json.requiredPath(root, "rootLocationPath");
        LocationKey key = LocationKey.parse(
                EvidenceJsonSupport.requiredString(root, "rootLocationKey"));
        JsonNode birthTime = EvidenceJsonSupport.requiredField(root, "rootBirthTime");
        EvidenceJsonSupport.requireObject(birthTime, "rootBirthTime");
        EvidenceJsonSupport.requireExactFields(birthTime, BIRTH_TIME_FIELDS);

        return new MacOsApfsSourceRootEvidence(
                EvidenceJsonSupport.requiredInt(root, "version"),
                EvidenceJsonSupport.requiredString(root, "profile"),
                EvidenceJsonSupport.requiredInt(root, "profileVersion"),
                EvidenceJsonSupport.requiredString(root, "locationContextId"),
                EvidenceJsonSupport.requiredLong(root, "locationContextRevision"),
                EvidenceJsonSupport.requiredLong(root, "sourceLocationRevision"),
                path,
                key,
                EvidenceJsonSupport.requiredString(root, "volumeUuid"),
                EvidenceJsonSupport.requiredString(root, "rootInode"),
                new MacOsApfsSourceRootEvidence.BirthTime(
                        EvidenceJsonSupport.requiredLong(birthTime, "epochSecond"),
                        EvidenceJsonSupport.requiredInt(birthTime, "nano")),
                EvidenceJsonSupport.requiredBoolean(root, "directory"),
                EvidenceJsonSupport.requiredBoolean(root, "symbolicLink"),
                EvidenceJsonSupport.requiredLong(root, "acceptedAtMs"));
    }
}
