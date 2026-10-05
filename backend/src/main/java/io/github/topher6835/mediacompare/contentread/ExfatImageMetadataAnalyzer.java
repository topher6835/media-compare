package io.github.topher6835.mediacompare.contentread;

import java.io.IOException;
import java.util.List;
import io.github.topher6835.mediacompare.analysis.*;
import org.springframework.stereotype.Component;

@Component
public class ExfatImageMetadataAnalyzer {
    public record Result(MediaMetadataResult metadata, boolean failed) { }
    private final MediaMetadataCache cache;
    private final MediaMetadataCandidateRepository candidates;
    private final ExfatContentReadCatalog catalog;
    private final ExfatProtectedOriginalAccess originals;
    private final ImageMetadataExtractor extractor;
    private final MediaMetadataPublisher publisher;
    public ExfatImageMetadataAnalyzer(MediaMetadataCache cache, MediaMetadataCandidateRepository candidates,
            ExfatContentReadCatalog catalog, ExfatProtectedOriginalAccess originals,
            ImageMetadataExtractor extractor, MediaMetadataPublisher publisher) {
        this.cache = cache; this.candidates = candidates; this.catalog = catalog;
        this.originals = originals; this.extractor = extractor; this.publisher = publisher;
    }
    public Result analyze(MediaMetadataContentCandidate content, List<ExfatContentReadAuthority> owners) {
        var definition = ImageIoMediaMetadataDefinition.definition();
        var reusable = cache.findReusableResult(content.contentRecordId(), definition);
        if (reusable.isPresent()) return new Result(reusable.get(), false);
        List<Long> sources = owners.stream().map(a -> a.window().sourceId()).toList();
        long after = 0;
        while (true) {
            var page = candidates.findExfatOccurrences(content, sources, after, MediaMetadataJobDefinition.CANDIDATE_BATCH_SIZE);
            if (page.isEmpty()) return new Result(null, false);
            for (var candidate : page) {
                after = candidate.fileEntryId();
                var owner = owners.stream().filter(a -> a.window().sourceId() == candidate.sourceId()).findFirst().orElseThrow();
                var capture = catalog.capture(owner, candidate.fileEntryId(), candidate.membershipId());
                long started = System.currentTimeMillis();
                try {
                    return originals.read(capture, input -> {
                        try { return new Result(extractor.extract(input), false); }
                        catch (ImageMetadataExtractionException malformed) { return new Result(null, true); }
                    }, (decoded, proof) -> {
                        var stored = publisher.publishProtected(capture, proof, candidate, definition, decoded.metadata(),
                                started, System.currentTimeMillis(), decoded.failed());
                        return "COMPLETED".equals(stored.status())
                                ? new Result(cache.findReusableResult(content.contentRecordId(), definition).orElseThrow(), false)
                                : decoded;
                    });
                } catch (IOException failure) {
                    // Authority/hash/native failures never become attributed decoder failures.
                    throw new IllegalStateException("Protected metadata read failed", failure);
                }
            }
        }
    }
}
