package io.github.topher6835.mediacompare.analysis;

public class FfprobeRunnerInterruptedException extends RuntimeException {

    FfprobeRunnerInterruptedException() {
        super("ffprobe execution interrupted");
    }
}
