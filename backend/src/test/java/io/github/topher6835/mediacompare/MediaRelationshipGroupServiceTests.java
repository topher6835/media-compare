package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.matching.MediaRelationship;
import io.github.topher6835.mediacompare.matching.MediaRelationshipDefinition;
import io.github.topher6835.mediacompare.matching.MediaRelationshipDirection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipGroupService;
import io.github.topher6835.mediacompare.matching.MediaRelationshipGrouping;
import io.github.topher6835.mediacompare.matching.MediaRelationshipRepository;
import io.github.topher6835.mediacompare.matching.MediaRelationshipSelection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:relationship-group-tests-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaRelationshipGroupServiceTests {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CatalogRepository catalog;
    @Autowired private MediaRelationshipRepository relationships;
    @Autowired private MediaRelationshipGroupService groups;

    @AfterEach
    void clearRows() {
        jdbcTemplate.update("DELETE FROM media_relationship");
        jdbcTemplate.update("DELETE FROM content_record");
    }

    @Test
    void staleHistoricalBridgeCannotConnectCurrentGroupsAndOldSelectionChangesGraph() {
        long a = content();
        long b = content();
        long c = content();
        long d = content();
        MediaRelationshipDefinition crop = definition(MediaRelationshipType.CROP);
        MediaRelationshipDefinition resized = definition(MediaRelationshipType.RESIZED);
        // Higher versions and a later timestamp deliberately do not mean compatible/current.
        MediaRelationshipDefinition oldCrop = new MediaRelationshipDefinition(
                MediaRelationshipType.CROP, "test.crop", "99", 99, "old-hash", "{\"old\":true}");
        insert(a, b, crop, MediaRelationshipDirection.A_TO_B, 1);
        insert(b, c, oldCrop, MediaRelationshipDirection.B_TO_A, 999);
        insert(c, d, resized, MediaRelationshipDirection.UNDIRECTED, 1);
        List<MediaRelationship> before = history();
        List<MediaRelationshipDefinition> input = new ArrayList<>(List.of(crop, resized));
        List<MediaRelationshipDefinition> inputBefore = List.copyOf(input);
        MediaRelationshipSelection selection = new MediaRelationshipSelection(input);
        List<MediaRelationshipDefinition> selectedBefore = selection.definitions();

        assertEquals(List.of(List.of(a, b), List.of(c, d)), groups.group(selection));
        assertEquals(List.of(List.of(b, c, d)), groups.group(new MediaRelationshipSelection(List.of(oldCrop, resized))));
        // This retained-history graph demonstrates the bridge that the service must exclude.
        assertEquals(List.of(List.of(a, b, c, d)), new MediaRelationshipGrouping().group(before, selection.enabledTypes()));
        assertEquals(before, history());
        assertEquals(inputBefore, input);
        assertEquals(selectedBefore, selection.definitions());
    }

    @Test
    void selectedMixedTypesConnectTransitivelyAndOmittingATypeSplitsComponents() {
        long a = content();
        long b = content();
        long c = content();
        long d = content();
        MediaRelationshipDefinition crop = definition(MediaRelationshipType.CROP);
        MediaRelationshipDefinition resized = definition(MediaRelationshipType.RESIZED);
        insert(a, b, crop, MediaRelationshipDirection.UNDIRECTED, 1);
        insert(b, c, resized, MediaRelationshipDirection.UNDIRECTED, 1);
        insert(c, d, crop, MediaRelationshipDirection.UNDIRECTED, 1);
        List<MediaRelationship> before = history();

        assertEquals(List.of(List.of(a, b, c, d)), groups.group(new MediaRelationshipSelection(List.of(crop, resized))));
        assertEquals(List.of(List.of(a, b), List.of(c, d)), groups.group(new MediaRelationshipSelection(List.of(crop))));
        assertEquals(List.of(List.of(b, c)), groups.group(new MediaRelationshipSelection(List.of(resized))));
        assertEquals(List.of(), groups.group(new MediaRelationshipSelection(List.of())));
        assertEquals(before, history());
    }

    @Test
    void directionDoesNotLimitConnectivityThroughTheSelectionBoundary() {
        MediaRelationshipDefinition crop = definition(MediaRelationshipType.CROP);
        MediaRelationshipSelection selection = new MediaRelationshipSelection(List.of(crop));
        for (MediaRelationshipDirection direction : MediaRelationshipDirection.values()) {
            long a = content();
            long b = content();
            long c = content();
            insert(b, a, crop, direction, 1);
            insert(b, c, crop, direction, 1);
            assertEquals(List.of(a, b, c), groups.group(selection).getLast());
        }
    }

    private long content() {
        return catalog.insert(new ContentRecord(null, 42, 1)).id();
    }

    private List<MediaRelationship> history() {
        return relationships.findByTypes(EnumSet.allOf(MediaRelationshipType.class));
    }

    private void insert(long a, long b, MediaRelationshipDefinition definition,
            MediaRelationshipDirection direction, long createdAtMs) {
        relationships.insert(new MediaRelationship(null, a, b, definition.relationshipType(), direction,
                null, "{}", definition.matcherId(), definition.matcherVersion(), definition.configurationVersion(),
                definition.configurationHash(), definition.configurationJson(), createdAtMs));
    }

    private static MediaRelationshipDefinition definition(MediaRelationshipType type) {
        return new MediaRelationshipDefinition(type, "test." + type.name().toLowerCase(java.util.Locale.ROOT),
                "1", 1, "hash", "{}");
    }
}
