package io.github.topher6835.mediacompare.catalog;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class SourceService {

    private final CatalogRepository catalogRepository;

    public SourceService(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public Source register(String name, String rootPath) {
        validateName(name);
        validateRootPath(rootPath);

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
