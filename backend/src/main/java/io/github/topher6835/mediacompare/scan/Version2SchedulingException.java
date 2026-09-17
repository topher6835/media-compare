package io.github.topher6835.mediacompare.scan;

public class Version2SchedulingException extends RuntimeException {
    public Version2SchedulingException(Throwable cause) {
        super("Execution could not be scheduled", cause);
    }
}
