package io.github.topher6835.mediacompare.management;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reuses this installation's JVM and application, without a shell or a machine-specific path. */
final class CatalogChildCommand {
    private CatalogChildCommand() { }

    static List<String> currentApplication() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("java.class.path");
        if (!classpath.contains(File.pathSeparator) && classpath.endsWith(".jar")
                && Files.isRegularFile(Path.of(classpath))) {
            return List.of(java, "-jar", Path.of(classpath).toAbsolutePath().toString());
        }
        return List.of(java, "-cp", classpath, "io.github.topher6835.mediacompare.MediaCompareApplication");
    }
}
