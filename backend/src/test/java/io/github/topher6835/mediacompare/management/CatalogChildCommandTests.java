package io.github.topher6835.mediacompare.management;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class CatalogChildCommandTests {
    @Test void sessionPathRoundTripsAsAsciiWithoutChangingItsSpelling() {
        for (String name : new String[] { "Session", "Session with spaces", "Café 日本 🎬 + # % ! & (2026)" }) {
            Path path = Path.of(name).toAbsolutePath().normalize();
            String argument = CatalogChildCommand.sessionArgument(path);
            assertTrue(argument.chars().allMatch(c -> c < 128));
            assertEquals(path.toString(), CatalogChildCommand.sessionPath(argument));
        }
    }

    @Test void malformedTransportIsRejectedRatherThanReplacedOrTreatedAsARawPath() {
        assertThrows(IllegalArgumentException.class, () -> CatalogChildCommand.sessionPath("C:\\raw path"));
        assertThrows(IllegalArgumentException.class, () -> CatalogChildCommand.sessionPath("session-path-utf8-v1:!"));
        // Base64 is valid, but the decoded bytes are not valid UTF-8.
        assertThrows(IllegalArgumentException.class, () -> CatalogChildCommand.sessionPath("session-path-utf8-v1:_w"));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void actualWindowsJavaMainReceivesLosslessPathThroughEncodedArgument() throws Exception {
        Path path = Path.of("Café 日本 🎬 + # % ! & (2026)").toAbsolutePath().normalize();
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"), CatalogChildCommandTests.class.getName(),
                CatalogChildCommand.sessionArgument(path)).redirectErrorStream(true).start();
        child.getOutputStream().close();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Argument probe child did not exit");
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
        assertEquals(0, child.exitValue(), output);
        assertEquals(Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8)), output);
    }

    public static void main(String[] args) {
        System.out.println(Base64.getEncoder().encodeToString(
                CatalogChildCommand.sessionPath(args[0]).getBytes(StandardCharsets.UTF_8)));
    }
}
