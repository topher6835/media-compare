package io.github.topher6835.mediacompare.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

record MacOsContinuityEvidence(
        String providerClass,
        String fileStoreName,
        String fileStoreType,
        long device,
        long inode,
        long creationTimeNanos,
        String fileKeyDiagnostic,
        boolean directory,
        boolean symbolicLink) {

    static MacOsContinuityEvidence capture(Path path) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Map<String, Object> unix = Files.readAttributes(
                path, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
        FileStore store = Files.getFileStore(path);
        Object fileKey = basic.fileKey();
        return new MacOsContinuityEvidence(
                path.getFileSystem().provider().getClass().getName(),
                store.name(),
                store.type(),
                number(unix, "dev"),
                number(unix, "ino"),
                basic.creationTime().to(TimeUnit.NANOSECONDS),
                fileKey == null ? "" : fileKey.toString(),
                basic.isDirectory(),
                basic.isSymbolicLink());
    }

    ContextRequiredEvidence contextRequiredEvidence() {
        return new ContextRequiredEvidence(fileStoreType, inode, directory, symbolicLink);
    }

    SourceRequiredEvidence sourceRequiredEvidence() {
        return new SourceRequiredEvidence(
                fileStoreType,
                inode,
                creationTimeNanos,
                directory,
                symbolicLink);
    }

    OptionalDiagnosticEvidence optionalDiagnosticEvidence() {
        return new OptionalDiagnosticEvidence(providerClass, fileStoreName, device);
    }

    RejectedContinuityEvidence rejectedContinuityEvidence() {
        return new RejectedContinuityEvidence(fileKeyDiagnostic);
    }

    String encode() {
        return String.join("\t",
                encodeText(providerClass),
                encodeText(fileStoreName),
                encodeText(fileStoreType),
                Long.toString(device),
                Long.toString(inode),
                Long.toString(creationTimeNanos),
                encodeText(fileKeyDiagnostic),
                Boolean.toString(directory),
                Boolean.toString(symbolicLink));
    }

    static MacOsContinuityEvidence decode(String encoded) {
        String[] fields = encoded.split("\t", -1);
        if (fields.length != 9) {
            throw new IllegalArgumentException("Expected nine continuity evidence fields");
        }
        return new MacOsContinuityEvidence(
                decodeText(fields[0]),
                decodeText(fields[1]),
                decodeText(fields[2]),
                Long.parseLong(fields[3]),
                Long.parseLong(fields[4]),
                Long.parseLong(fields[5]),
                decodeText(fields[6]),
                Boolean.parseBoolean(fields[7]),
                Boolean.parseBoolean(fields[8]));
    }

    private static long number(Map<String, Object> attributes, String name) throws IOException {
        Object value = attributes.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IOException("macOS Unix attribute is not numeric: " + name);
    }

    private static String encodeText(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    record ContextRequiredEvidence(
            String fileStoreType,
            long inode,
            boolean directory,
            boolean symbolicLink) {
    }

    record SourceRequiredEvidence(
            String fileStoreType,
            long inode,
            long creationTimeNanos,
            boolean directory,
            boolean symbolicLink) {
    }

    record OptionalDiagnosticEvidence(
            String providerClass,
            String fileStoreName,
            long device) {
    }

    record RejectedContinuityEvidence(String fileKey) {
    }
}
