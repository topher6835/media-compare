package io.github.topher6835.mediacompare.catalog;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.github.topher6835.mediacompare.session.SessionSourceBoundary;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;

@Service
@DependsOnDatabaseInitialization
public class SourceService implements InitializingBean {

    private final CatalogRepository catalogRepository;
    private final SessionSourceBoundary sessionBoundary;

    public SourceService(CatalogRepository catalogRepository, SessionSourceBoundary sessionBoundary) {
        this.catalogRepository = catalogRepository;
        this.sessionBoundary = sessionBoundary;
    }

    @Override
    public void afterPropertiesSet() {
        // A moved/copied Session may already contain Sources that now overlap its folder.
        for (Source source : catalogRepository.findAllSources()) {
            sessionBoundary.requireSeparate(source.rootPath());
        }
    }

    public Source register(String name, String rootPath) {
        validateName(name);
        validateRootPath(rootPath);
        sessionBoundary.requireSeparate(rootPath);

        long now = System.currentTimeMillis();
        Source source = new Source(null, name, rootPath, rootPath, 0, now, now);
        return catalogRepository.insert(source);
    }

    public List<Source> findAll() {
        return catalogRepository.findAllSources();
    }

    public Optional<Source> findById(long id) {
        return catalogRepository.findSourceById(id);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Source name must not be blank");
        }
    }

    private static void validateRootPath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("Source root path must not be blank");
        }

        try {
            if (!Path.of(rootPath).isAbsolute()) {
                throw new IllegalArgumentException("Source root path must be absolute");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Source root path is not syntactically valid", exception);
        }
    }
}
