package io.github.topher6835.mediacompare.web;

import java.io.IOException;

import io.github.topher6835.mediacompare.preview.PreviewAssetKey;
import io.github.topher6835.mediacompare.preview.PreviewAssetRepository;
import io.github.topher6835.mediacompare.preview.PreviewCacheResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/previews")
public class PreviewController {
    private static final Logger log = LoggerFactory.getLogger(PreviewController.class);
    private final PreviewAssetRepository assets;
    private final PreviewCacheResolver cache;

    public PreviewController(PreviewAssetRepository assets, PreviewCacheResolver cache) {
        this.assets = assets;
        this.cache = cache;
    }

    @GetMapping("/{assetKey}")
    public ResponseEntity<Resource> get(@PathVariable String assetKey) {
        try {
            PreviewAssetKey.requireValid(assetKey);
        } catch (IllegalArgumentException invalidKey) {
            return error(400);
        }
        try {
            var found = assets.findByAssetKey(assetKey);
            if (found.isEmpty()) {
                return error(404);
            }
            var asset = found.get();
            cache.requireRegularFile(asset.relativePath(), asset.assetSizeBytes());
            // Lazy opening also avoids holding a file handle for conditional 304 responses.
            Resource body = new InputStreamResource(() -> cache.open(asset.relativePath(), asset.assetSizeBytes()));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                    .eTag(assetKey)
                    .contentType(MediaType.parseMediaType(asset.mediaType()))
                    .contentLength(asset.assetSizeBytes())
                    .body(body);
        } catch (IOException | IllegalArgumentException | SecurityException | UnsupportedOperationException unsafe) {
            return error(404);
        } catch (RuntimeException failure) {
            log.error("Preview API lookup failed", failure);
            return error(500);
        }
    }

    private static ResponseEntity<Resource> error(int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
