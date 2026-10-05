package io.github.topher6835.mediacompare.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import io.github.topher6835.mediacompare.contentread.*;
import org.springframework.stereotype.Component;
import static io.github.topher6835.mediacompare.preview.ThumbnailGenerationResult.Outcome.*;

@Component
public class ExfatThumbnailService {
    private final PreviewAssetRepository assets;
    private final PreviewCacheWriter cache;
    private final SmallThumbnailRenderer renderer;
    private final ThumbnailPublisher publisher;
    private final ExfatProtectedOriginalAccess originals;
    public ExfatThumbnailService(PreviewAssetRepository assets, PreviewCacheWriter cache,
            SmallThumbnailRenderer renderer, ThumbnailPublisher publisher, ExfatProtectedOriginalAccess originals) {
        this.assets = assets; this.cache = cache; this.renderer = renderer;
        this.publisher = publisher; this.originals = originals;
    }
    public Optional<ThumbnailGenerationResult> cached(long fileId) {
        var asset = assets.findCurrent(fileId, SmallThumbnailDefinition.definition(), PreviewKind.SMALL_THUMBNAIL);
        if (asset.isEmpty()) return Optional.empty();
        try { cache.validatePublished(asset.get()); return Optional.of(new ThumbnailGenerationResult(REUSED, asset.get())); }
        catch (IOException invalid) { return Optional.empty(); }
    }

    public ThumbnailGenerationResult generate(ExfatContentReadCapture capture) {
        var cached = cached(capture.file().id());
        if (cached.isPresent()) {
            var file = capture.file();
            var evidence = cached.get().asset().evidence();
            if (evidence.contentRecordId() != capture.content().id()
                    || evidence.observationRevision() != file.observationRevision()) throw ExfatContentReadCatalog.unavailable();
            return cached.get();
        }
        var f = capture.file();
        var evidence = new PreviewSourceEvidence(f.id(), capture.content().id(), f.observationRevision(),
                f.sizeBytes(), f.modifiedTimeEpochSecond(), f.modifiedTimeNano());
        var definition = SmallThumbnailDefinition.definition();
        String key = PreviewAssetKey.compute(evidence, PreviewKind.SMALL_THUMBNAIL, definition);
        String relative = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, SmallThumbnailDefinition.EXTENSION);
        Path temporary = null;
        try {
            temporary = cache.createTemporary(relative, key);
            final Path output = temporary;
            return originals.read(capture, input -> {
                var rendered = renderer.render(input, output);
                if (rendered.isEmpty()) return null;
                var dimensions = rendered.get();
                long bytes = cache.validateTemporary(output, dimensions);
                var generated = new PreviewAsset(null, key, evidence, PreviewKind.SMALL_THUMBNAIL, definition,
                        relative, SmallThumbnailDefinition.MEDIA_TYPE, dimensions.width(), dimensions.height(), bytes,
                        System.currentTimeMillis());
                return new Prepared(generated, cache.preparePublication(output, generated));
            }, (decoded, proof) -> decoded == null ? new ThumbnailGenerationResult(UNSUPPORTED, null)
                    : new ThumbnailGenerationResult(GENERATED, publisher.publishProtected(capture, proof, decoded.asset(), decoded.file())));
        } catch (IOException failure) { throw new ThumbnailGenerationException("Protected thumbnail read/publication failed", failure); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
    private record Prepared(PreviewAsset asset, PreviewCacheWriter.PreparedPublication file) { }
}
