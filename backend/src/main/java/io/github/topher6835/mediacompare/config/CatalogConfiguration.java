package io.github.topher6835.mediacompare.config;

import java.io.IOException;
import java.nio.file.Path;
import javax.sql.DataSource;

import io.github.topher6835.mediacompare.preview.PreviewCacheResolver;
import io.github.topher6835.mediacompare.preview.PreviewCacheWriter;
import io.github.topher6835.mediacompare.session.Session;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {
    @Bean
    CatalogPaths catalogPaths(Environment environment) throws IOException {
        String selectedRoot = environment.getProperty("media-compare.session-root");
        if (selectedRoot != null && !selectedRoot.isBlank()) {
            Session session = Session.open(Path.of(selectedRoot));
            return new CatalogPaths("jdbc:sqlite:" + session.catalogPath().toUri() + "?foreign_keys=on",
                    session.previewCacheRoot().toString());
        }
        // Existing isolated catalog tests explicitly opt into their own JDBC/cache paths.
        if (environment.getProperty("media-compare.test-catalog-override", Boolean.class, false)) {
            String jdbcUrl = environment.getProperty("spring.datasource.url");
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalStateException("Test catalog override requires spring.datasource.url");
            }
            return new CatalogPaths(jdbcUrl,
                    environment.getProperty("media-compare.preview-cache-root", "data/cache/previews"));
        }
        throw new IllegalStateException("Set media-compare.session-root to an existing Session folder before startup");
    }

    @Bean
    CatalogOwnership catalogOwnership(CatalogPaths paths) throws IOException {
        return CatalogOwnership.acquire(paths.jdbcUrl());
    }

    @Bean
    DataSource dataSource(CatalogOwnership ownership, CatalogPaths paths) {
        // Ownership precedes even Flyway writes; one URL determines both database and lock.
        return DataSourceBuilder.create().url(paths.jdbcUrl()).driverClassName("org.sqlite.JDBC").build();
    }

    @Bean
    PreviewCacheResolver previewCacheResolver(CatalogPaths paths) {
        return new PreviewCacheResolver(paths.previewCacheRoot());
    }

    @Bean
    PreviewCacheWriter previewCacheWriter(CatalogPaths paths, PreviewCacheResolver resolver) {
        return new PreviewCacheWriter(paths.previewCacheRoot(), resolver);
    }
}
