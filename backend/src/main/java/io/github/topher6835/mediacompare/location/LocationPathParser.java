package io.github.topher6835.mediacompare.location;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Pure parser for the deliberately restricted supported address grammars. */
public final class LocationPathParser {
    private LocationPathParser() {
    }

    public static LocationPath parse(LocationDialect dialect, String input) {
        Objects.requireNonNull(dialect, "dialect");
        LocationPathValidation.requireWellFormedUnicode(input, "Location path");
        if (input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Location path must not contain NUL");
        }
        return switch (dialect) {
            case UNIX -> parseUnix(input);
            case WINDOWS_DRIVE -> parseWindowsDrive(input);
            case WINDOWS_UNC -> parseWindowsUnc(input);
        };
    }

    private static LocationPath parseUnix(String input) {
        if (!input.startsWith("/")) {
            throw new IllegalArgumentException("Unix location must be absolute");
        }
        if ("/".equals(input)) {
            return new LocationPath(LocationDialect.UNIX, List.of(), List.of());
        }
        if (input.endsWith("/")) {
            throw new IllegalArgumentException("Non-root Unix location must not have a trailing separator");
        }
        return new LocationPath(LocationDialect.UNIX, List.of(), splitExact(input.substring(1), '/'));
    }

    private static LocationPath parseWindowsDrive(String input) {
        if (input.length() < 3 || !isAsciiLetter(input.charAt(0))
                || input.charAt(1) != ':' || !isWindowsSeparator(input.charAt(2))) {
            throw new IllegalArgumentException("Windows drive location must be drive-rooted");
        }
        String drive = input.substring(0, 1);
        if (input.length() == 3) {
            return new LocationPath(LocationDialect.WINDOWS_DRIVE, List.of(drive), List.of());
        }
        if (isWindowsSeparator(input.charAt(input.length() - 1))) {
            throw new IllegalArgumentException("Non-root Windows location must not have a trailing separator");
        }
        return new LocationPath(LocationDialect.WINDOWS_DRIVE, List.of(drive),
                splitWindows(input.substring(3)));
    }

    private static LocationPath parseWindowsUnc(String input) {
        if (input.length() < 5 || !isWindowsSeparator(input.charAt(0))
                || !isWindowsSeparator(input.charAt(1))
                || isWindowsSeparator(input.charAt(2))) {
            throw new IllegalArgumentException("UNC location must begin with exactly two separators");
        }
        if (input.startsWith("\\\\?\\") || input.startsWith("\\\\.\\")
                || input.startsWith("//?/") || input.startsWith("//./")) {
            throw new IllegalArgumentException("Windows device namespaces are not supported");
        }

        List<String> fields = splitWindows(input.substring(2));
        if (fields.size() < 2) {
            throw new IllegalArgumentException("UNC location requires server and share fields");
        }
        return new LocationPath(LocationDialect.WINDOWS_UNC,
                List.of(fields.get(0), fields.get(1)), fields.subList(2, fields.size()));
    }

    private static List<String> splitExact(String value, char separator) {
        return Arrays.asList(value.split(Pattern.quote(Character.toString(separator)), -1));
    }

    private static List<String> splitWindows(String value) {
        return Arrays.asList(value.split("[/\\\\]", -1));
    }

    private static boolean isWindowsSeparator(char character) {
        return character == '/' || character == '\\';
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }
}
