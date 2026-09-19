package io.github.topher6835.mediacompare.platform;

import java.nio.file.Path;

public final class MacOsContinuityProbeMain {

    private MacOsContinuityProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one path argument");
        }
        System.out.println(MacOsContinuityEvidence.capture(Path.of(args[0])).encode());
    }
}
