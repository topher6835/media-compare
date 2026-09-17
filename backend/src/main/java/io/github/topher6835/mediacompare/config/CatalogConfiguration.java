package io.github.topher6835.mediacompare.config;

import java.io.IOException;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {
    @Bean
    CatalogOwnership catalogOwnership(@Value("${spring.datasource.url}") String jdbcUrl) throws IOException {
        return CatalogOwnership.acquire(jdbcUrl);
    }

    @Bean
    DataSource dataSource(CatalogOwnership ownership, @Value("${spring.datasource.url}") String jdbcUrl) {
        // Ownership precedes even Flyway writes; one URL determines both database and lock.
        return DataSourceBuilder.create().url(jdbcUrl).driverClassName("org.sqlite.JDBC").build();
    }
}
