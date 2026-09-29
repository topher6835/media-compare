package io.github.topher6835.mediacompare.matching;

import static io.github.topher6835.mediacompare.matching.MediaRelationshipValidation.requireConfigurationVersion;
import static io.github.topher6835.mediacompare.matching.MediaRelationshipValidation.requireJsonObject;
import static io.github.topher6835.mediacompare.matching.MediaRelationshipValidation.requireNonBlank;

import java.util.Objects;

/** One exact producing definition; configuration JSON is compared as supplied, without normalization. */
public record MediaRelationshipDefinition(
        MediaRelationshipType relationshipType,
        String matcherId,
        String matcherVersion,
        long configurationVersion,
        String configurationHash,
        String configurationJson) {

    public MediaRelationshipDefinition {
        Objects.requireNonNull(relationshipType, "relationshipType");
        requireNonBlank(matcherId, "matcherId");
        requireNonBlank(matcherVersion, "matcherVersion");
        requireConfigurationVersion(configurationVersion);
        requireNonBlank(configurationHash, "configurationHash");
        requireJsonObject(configurationJson, "configurationJson");
    }

    public boolean matches(MediaRelationship relationship) {
        Objects.requireNonNull(relationship, "relationship");
        return relationshipType == relationship.relationshipType()
                && matcherId.equals(relationship.matcherId())
                && matcherVersion.equals(relationship.matcherVersion())
                && configurationVersion == relationship.configurationVersion()
                && configurationHash.equals(relationship.configurationHash())
                && configurationJson.equals(relationship.configurationJson());
    }
}
