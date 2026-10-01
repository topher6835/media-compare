package io.github.topher6835.mediacompare.filesystem;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Real-volume acceptance fixtures live on the checkout's drive, not the OS temp drive. */
public class CheckoutTempDirFactory implements TempDirFactory {
    @Override
    public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws Exception {
        return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "ntfs-acceptance-");
    }
}
