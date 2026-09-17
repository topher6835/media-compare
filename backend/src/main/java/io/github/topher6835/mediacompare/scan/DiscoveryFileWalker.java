package io.github.topher6835.mediacompare.scan;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

@Component
public class DiscoveryFileWalker {

    public void walk(Path root, Consumer<DiscoveredFile> observer) throws IOException {
        BasicFileAttributes rootAttributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!rootAttributes.isDirectory()) {
            throw new NotDirectoryException(root.toString());
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                IndexingInterruptedException.check();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                IndexingInterruptedException.check();
                if (attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                    Path relativePath = root.relativize(file);
                    Instant modifiedTime = attributes.lastModifiedTime().toInstant();
                    observer.accept(new DiscoveredFile(
                            toPortablePath(relativePath),
                            attributes.size(),
                            modifiedTime.getEpochSecond(),
                            modifiedTime.getNano()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String toPortablePath(Path relativePath) {
        StringBuilder portablePath = new StringBuilder();
        for (Path segment : relativePath) {
            if (!portablePath.isEmpty()) {
                portablePath.append('/');
            }
            portablePath.append(segment);
        }
        return portablePath.toString();
    }
}
