package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Fail-closed policy for LocationContext anchor pairs. Structural overlap marks a conflicting address domain only; it never proves filesystem continuity. */
public final class LocationAnchorPolicy {
    private static final LocationPathCodec PATH_CODEC = new LocationPathCodec();

    private LocationAnchorPolicy() {
    }

    /** Decodes a persisted anchor pair and fails closed unless both forms address the same structured location. */
    public static LocationPath validateAnchor(String anchorLocationPath, String anchorLocationKey) {
        Objects.requireNonNull(anchorLocationPath, "anchorLocationPath");
        Objects.requireNonNull(anchorLocationKey, "anchorLocationKey");
        LocationPath path = PATH_CODEC.decode(anchorLocationPath);
        LocationPath keyed = LocationKeyCodec.decode(LocationKey.parse(anchorLocationKey));
        if (!path.equals(keyed)) {
            throw new IllegalArgumentException("Anchor path and anchor key address different locations");
        }
        return path;
    }

    /** Returns true when the anchors are identical or one structurally contains the other. */
    public static boolean structurallyOverlaps(LocationPath left, LocationPath right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.contains(right) || right.contains(left);
    }
}