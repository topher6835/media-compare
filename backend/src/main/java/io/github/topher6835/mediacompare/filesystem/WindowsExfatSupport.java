package io.github.topher6835.mediacompare.filesystem;

/** No runtime configuration can enable the incomplete production stack. */
public final class WindowsExfatSupport {
    public static final WindowsExfatSupport PRODUCTION = new WindowsExfatSupport(false);
    private final boolean available;

    // Package-local injection for native-boundary tests; production constructors use PRODUCTION.
    WindowsExfatSupport(boolean available) { this.available = available; }

    public boolean available() { return available; }

    public void requireAvailable() throws WindowsFileAccessException {
        if (!available) throw new WindowsFileAccessException(
                WindowsFileAccessException.Reason.UNSUPPORTED, "Production exFAT access is disabled");
    }
}
