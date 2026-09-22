package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LocationAnchorPolicyTests {
    private final LocationPathCodec pathCodec = new LocationPathCodec();

    @Test
    void validatesConsistentAnchorsForAllDialects() {
        for (LocationPath anchor : List.of(
                unix("/Volumes/Archive"),
                drive("C:\\Photos"),
                unc("\\\\server\\share\\Photos"))) {
            assertEquals(anchor, LocationAnchorPolicy.validateAnchor(
                    pathCodec.encode(anchor), LocationKeyCodec.encode(anchor).value()));
        }
    }

    @Test
    void preservesExactUnicodeSpellingInValidatedAnchor() {
        LocationPath anchor = unix("/Caf\u00e9/Photos");
        LocationPath validated = LocationAnchorPolicy.validateAnchor(
                pathCodec.encode(anchor), LocationKeyCodec.encode(anchor).value());
        assertEquals(List.of("Caf\u00e9", "Photos"), validated.components());
    }

    @Test
    void failsClosedOnMalformedAnchorPath() {
        LocationPath anchor = unix("/Volumes/Archive");
        assertThrows(IllegalArgumentException.class, () -> LocationAnchorPolicy.validateAnchor(
                "not-json", LocationKeyCodec.encode(anchor).value()));
    }

    @Test
    void failsClosedOnMalformedAnchorKey() {
        LocationPath anchor = unix("/Volumes/Archive");
        assertThrows(IllegalArgumentException.class, () -> LocationAnchorPolicy.validateAnchor(
                pathCodec.encode(anchor), "lk1:zz"));
    }

    @Test
    void failsClosedWhenPathAndKeyAddressDifferentLocations() {
        assertThrows(IllegalArgumentException.class, () -> LocationAnchorPolicy.validateAnchor(
                pathCodec.encode(unix("/Volumes/Archive")),
                LocationKeyCodec.encode(unix("/Volumes/Archive/Photos")).value()));
    }

    @Test
    void overlapsExactSameAnchor() {
        LocationPath anchor = unix("/Volumes/Archive");
        assertTrue(LocationAnchorPolicy.structurallyOverlaps(anchor, anchor));
    }

    @Test
    void overlapsUnixAncestorAndDescendantInBothOrders() {
        LocationPath ancestor = unix("/Volumes/Archive");
        LocationPath descendant = unix("/Volumes/Archive/Photos");
        assertTrue(LocationAnchorPolicy.structurallyOverlaps(ancestor, descendant));
        assertTrue(LocationAnchorPolicy.structurallyOverlaps(descendant, ancestor));
    }

    @Test
    void doesNotOverlapUnixSiblings() {
        assertFalse(LocationAnchorPolicy.structurallyOverlaps(
                unix("/Volumes/Archive/Photos"), unix("/Volumes/Archive/Videos")));
    }

    @Test
    void doesNotOverlapDifferentUnixRoots() {
        assertFalse(LocationAnchorPolicy.structurallyOverlaps(
                unix("/Volumes/Archive"), unix("/Volumes/Backup")));
    }

    @Test
    void overlapsWindowsDriveAncestorAndDescendantInBothOrders() {
        LocationPath root = drive("C:\\");
        LocationPath child = drive("C:\\Photos");
        assertTrue(LocationAnchorPolicy.structurallyOverlaps(root, child));
        assertTrue(LocationAnchorPolicy.structurallyOverlaps(child, root));
    }

    @Test
    void doesNotOverlapDifferentWindowsDriveRoots() {
        assertFalse(LocationAnchorPolicy.structurallyOverlaps(drive("C:\\"), drive("D:\\")));
    }

    @Test
    void overlapsUncSameShareAncestorAndDescendantInBothOrders() {
        LocationPath share = unc("\\\\server\\share");
        LocationPath child = unc("\\\\server\\share\\Photos");
        assertTrue(LocationAnchorPolicy.structurallyOverlaps(share, child));
        assertTrue(LocationAnchorPolicy.structurallyOverlaps(child, share));
    }

    @Test
    void doesNotOverlapDifferentUncShares() {
        assertFalse(LocationAnchorPolicy.structurallyOverlaps(
                unc("\\\\server\\share-a"), unc("\\\\server\\share-b")));
    }

    @Test
    void doesNotOverlapDifferentDialects() {
        assertFalse(LocationAnchorPolicy.structurallyOverlaps(
                unix("/Photos"), drive("C:\\Photos")));
    }

    @Test
    void doesNotOverlapDifferentCaseSpelling() {
        assertFalse(LocationAnchorPolicy.structurallyOverlaps(
                unix("/Volumes/Archive"), unix("/Volumes/ARCHIVE")));
    }

    @Test
    void doesNotOverlapUnicodeSpellingVariants() {
        assertFalse(LocationAnchorPolicy.structurallyOverlaps(
                unix("/Caf\u00e9"), unix("/Cafe\u0301")));
    }

    private static LocationPath unix(String input) {
        return LocationPathParser.parse(LocationDialect.UNIX, input);
    }

    private static LocationPath drive(String input) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, input);
    }

    private static LocationPath unc(String input) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_UNC, input);
    }
}