package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import io.github.topher6835.mediacompare.filesystem.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ExactDuplicateCapabilityTests {
    @ParameterizedTest @ValueSource(strings = {"native-path", "native-no-path", "exfat-path"})
    void occurrenceCapabilityRequiresUsablePathAndNonExfatEvidence(String scenario) throws Exception {
        var receipt = ExfatReceiptTestFixtures.receipt();
        var fields = new HashMap<String, Object>();
        fields.put("file_entry_id", 1L); fields.put("content_record_id", 1L);
        fields.put("current_content_id", 1L); fields.put("source_id", 1L); fields.put("membership_id", 1L);
        fields.put("source_name", "Photos"); fields.put("extension_key", "jpg");
        fields.put("presence_status", "PRESENT"); fields.put("applicability_status", "ACTIVE");
        fields.put("root_path", "X:\\photos"); fields.put("root_path_dialect", "win-drive");
        fields.put("root_path_key", ExfatReceiptTestFixtures.key(ExfatReceiptTestFixtures.ROOT));
        fields.put("relative_path", scenario.equals("native-no-path") ? "wrong-route.jpg" : "a.jpg");
        fields.put("location_path", receipt.locationPath()); fields.put("location_key", receipt.locationKey());
        fields.put("location_identity_status", "RESOLVED"); fields.put("location_context_id", receipt.contextId());
        fields.put("size_bytes", receipt.sizeBytes()); fields.put("observation_revision", receipt.fileObservationRevision());
        fields.put("modified_time_epoch_second", receipt.modifiedTimeEpochSecond());
        fields.put("modified_time_nano", receipt.modifiedTimeNano());
        if (scenario.equals("exfat-path")) {
            fields.put("occurrence_token", receipt.occurrenceToken());
            fields.put("observation_evidence_json", new ExfatObservationReceiptCodec().encode(receipt));
            var volume = new WindowsExfatVolumeEvidence("EXFAT", receipt.before().volumeSerial(), receipt.before().volumeGuid(), "X:\\");
            fields.put("physical_context_evidence", ExfatSlice3Fixtures.scope(1, receipt.contextId(), 1, 1,
                    ExfatReceiptTestFixtures.ROOT, volume).context().continuityEvidenceJson());
        }
        ResultSet row = mock(ResultSet.class);
        when(row.getString(anyString())).thenAnswer(call -> fields.get(call.getArgument(0)));
        when(row.getLong(anyString())).thenAnswer(call -> ((Number) fields.get(call.getArgument(0))).longValue());
        when(row.getObject(anyString(), any(Class.class))).thenAnswer(call -> fields.get(call.getArgument(0)));
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                try { return List.of(mapper.mapRow(row, 0)); }
                catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
            }
        };
        var occurrence = new ExactDuplicateRepository(jdbc).findOccurrences(receipt.sha256()).getFirst();
        if (scenario.equals("native-no-path")) assertNull(occurrence.absolutePath());
        else assertEquals("X:\\photos\\a.jpg", occurrence.absolutePath());
        boolean available = scenario.equals("native-path");
        assertEquals(available, occurrence.physicalActionsAvailable());
        assertEquals(available ? null : "AUTHORITY_UNAVAILABLE", occurrence.physicalActionsUnavailableReason());
        verify(row, times(1)).getString("physical_context_evidence");
    }
}
