package io.github.topher6835.mediacompare.library;

/** Total currently present physical FileEntries in a SHA-256 exact set. */
public record ExactSetReference(String digestHex, long physicalCopyCount) {}
