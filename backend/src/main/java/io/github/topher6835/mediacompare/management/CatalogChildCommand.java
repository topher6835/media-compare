package io.github.topher6835.mediacompare.management;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;

/** Reuses this installation's JVM and application, without a shell or a machine-specific path. */
final class CatalogChildCommand {
    private static final String SESSION_PATH_PREFIX = "session-path-utf8-v1:";

    private CatalogChildCommand() { }

    /** ASCII transport avoids native Windows argument conversion before the child's Java main. */
    static String sessionArgument(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("Session path must be valid Unicode");
        }
        return SESSION_PATH_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sessionPath(String argument) {
        if (!argument.startsWith(SESSION_PATH_PREFIX)) {
            throw new IllegalArgumentException("Catalog child requires an encoded Session path");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(argument.substring(SESSION_PATH_PREFIX.length()));
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid encoded Session path", failure);
        }
    }

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
