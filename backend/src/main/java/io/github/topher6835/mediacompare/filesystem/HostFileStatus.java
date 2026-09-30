package io.github.topher6835.mediacompare.filesystem;

/** Result of checking a current host path against catalog file evidence. */
public enum HostFileStatus {
    ESTABLISHED,
    MISSING,
    STALE,
    UNVERIFIABLE,
    UNSAFE_PATH
}
