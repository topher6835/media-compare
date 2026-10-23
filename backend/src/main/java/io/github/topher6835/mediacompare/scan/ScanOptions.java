package io.github.topher6835.mediacompare.scan;

import tools.jackson.databind.json.JsonMapper;

/** Immutable per-request scope. Version 1's historical empty object means recursive. */
public record ScanOptions(boolean includeSubfolders) {
    public static final long VERSION = 2;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String json() { return "{\"includeSubfolders\":" + includeSubfolders + "}"; }

    public static ScanOptions from(ScanRun run) {
        try {
            var node = JSON.readTree(run.optionsJson());
            if (run.optionsVersion() == 1 && node.isObject() && node.isEmpty()) return new ScanOptions(true);
            if (run.optionsVersion() == VERSION && node.isObject() && node.size() == 1
                    && node.has("includeSubfolders") && node.get("includeSubfolders").isBoolean()) {
                return new ScanOptions(node.get("includeSubfolders").booleanValue());
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid stored scan options", invalid);
        }
        throw new IllegalStateException("Unsupported stored scan options");
    }
}
