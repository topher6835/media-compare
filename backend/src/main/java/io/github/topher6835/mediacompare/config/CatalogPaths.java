package io.github.topher6835.mediacompare.config;

import java.nio.file.Path;

/** Paths from the one validated Session; sessionRoot is null only for isolated catalog tests. */
record CatalogPaths(String jdbcUrl, String previewCacheRoot, Path sessionRoot) {
}
