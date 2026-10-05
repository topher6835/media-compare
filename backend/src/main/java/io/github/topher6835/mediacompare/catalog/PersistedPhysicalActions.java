package io.github.topher6835.mediacompare.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import io.github.topher6835.mediacompare.filesystem.WindowsDurableEvidenceFormat;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec;
import org.springframework.stereotype.Component;

/** Catalog classification only. Live windows and host filesystem probes never authorize actions. */
@Component
public class PersistedPhysicalActions {
    private final SourceMembershipRepository files;
    private final LocationContextRepository contexts;
    public PersistedPhysicalActions(SourceMembershipRepository files, LocationContextRepository contexts) {
        this.files = files; this.contexts = contexts;
    }
    public boolean isExfat(long fileId) { return files.findById(fileId).map(this::isExfat).orElse(false); }
    public boolean isExfat(FileEntry file) {
        String evidence = file.locationContextId() == null ? null : contexts.findById(file.locationContextId())
                .map(LocationContext::continuityEvidenceJson).orElse(null);
        return isExfat(file, evidence);
    }
    public static boolean isExfat(FileEntry file, String contextEvidence) {
        boolean exfat = WindowsDurableEvidenceFormat.identify(contextEvidence) == WindowsDurableEvidenceFormat.EXFAT_CONTEXT;
        if (file.occurrenceToken() == null && file.observationEvidenceJson() == null && !exfat) return false;
        var receipt = OccurrenceProfileValidation.requireExfat(file);
        if (!exfat) throw new IllegalArgumentException("Occurrence evidence conflicts with context profile");
        var context = new WindowsExfatEvidenceCodec().decodeContext(contextEvidence);
        if (!context.contextId().equals(file.locationContextId())
                || !context.volume().volumeSerial().equals(receipt.before().volumeSerial())
                || !context.volume().volumeGuid().equals(receipt.before().volumeGuid())) {
            throw new IllegalArgumentException("Occurrence/context evidence conflicts");
        }
        return true;
    }

    /** Shared projection columns keep classification strict without per-row repository/host calls. */
    public static boolean projectionIsExfat(ResultSet row) throws SQLException {
        var file = new FileEntry(row.getLong("file_entry_id"), row.getString("location_identity_status"),
                row.getString("location_context_id"), row.getString("location_path"), row.getString("location_key"),
                row.getLong("current_content_id"), row.getLong("size_bytes"),
                row.getObject("modified_time_epoch_second", Long.class), row.getObject("modified_time_nano", Integer.class),
                row.getString("extension_key"), row.getLong("observation_revision"), 0, 0,
                row.getString("occurrence_token"), row.getString("observation_evidence_json"));
        return isExfat(file, row.getString("physical_context_evidence"));
    }
}
