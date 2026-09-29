package io.github.topher6835.mediacompare.config;

/** The one catalog and cache selected before database initialization. */
record CatalogPaths(String jdbcUrl, String previewCacheRoot) {
}
