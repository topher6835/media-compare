package io.github.topher6835.mediacompare.management;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Session-independent entry point. Never constructs the Spring catalog runtime. */
public final class ManagementApplication {
    private ManagementApplication() { }

    public static void main(String[] args) throws IOException {
        var arguments = new ArrayList<>(Arrays.asList(args));
        boolean openBrowser = !arguments.remove("--no-browser");
        String developmentOrigin = null;
        for (String argument : List.copyOf(arguments)) {
            if (argument.startsWith("--dev-origin=")) {
                if (developmentOrigin != null) throw new IllegalArgumentException("Only one development origin is allowed");
                developmentOrigin = argument.substring("--dev-origin=".length());
                arguments.remove(argument);
            }
        }
        if (arguments.isEmpty() || arguments.size() > 2) {
            throw new IllegalArgumentException("Usage: manage <frontend-dist> [port] [--no-browser] [--dev-origin=http://localhost:5173]");
        }
        int port = arguments.size() == 2 ? Integer.parseInt(arguments.get(1)) : 8081;
        ManagementServer server = ManagementServer.start(Path.of(arguments.getFirst()), port, developmentOrigin);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "media-compare-management-stop"));
        System.out.println("Media Compare management: " + server.baseUri());
        if (openBrowser) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(server.baseUri());
                } else {
                    System.out.println("Open the address above in your browser.");
                }
            } catch (IOException | RuntimeException exception) {
                // Browser launch failure must not stop the independent management service.
                System.out.println("Could not open the browser. Open the address above manually.");
            }
        }
    }
}
