package io.github.topher6835.mediacompare.analysis;

public record ContentHash(long analysisRecordId, String algorithm, String digestHex) {
}
