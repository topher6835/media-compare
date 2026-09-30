package io.github.topher6835.mediacompare.filesystem;

import java.util.Locale;

/** One explicit runtime selection point; unsupported hosts never inherit another host's rules. */
public final class HostFileSystems {
    private HostFileSystems() { }

    public static HostFileSystem current() {
        return select(System.getProperty("os.name"));
    }

    public static boolean isMacOs() {
        try {
            return current() instanceof MacOsHostFileSystem;
        } catch (UnsupportedOperationException exception) {
            return false;
        }
    }

    public static HostFileSystem select(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("mac os")) return new MacOsHostFileSystem();
        if (name.startsWith("windows")) return new WindowsNtfsHostFileSystem();
        throw new UnsupportedOperationException("Unsupported host platform: " + osName);
    }
}
