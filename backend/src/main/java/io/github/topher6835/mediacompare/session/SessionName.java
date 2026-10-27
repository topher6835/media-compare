package io.github.topher6835.mediacompare.session;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Portable single-component names; validation never changes user input. */
final class SessionName {
    // Reserve six units/bytes for " (999)" within a conservative 255-component limit.
    static final int MAX_LENGTH = 249;

    private SessionName() { }

    static void validate(String name) {
        if (name == null || name.codePoints().allMatch(code -> Character.isWhitespace(code)
                || Character.isSpaceChar(code)) || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Enter a nonempty Session name");
        }
        if (name.length() > MAX_LENGTH || name.getBytes(StandardCharsets.UTF_8).length > MAX_LENGTH) {
            throw new IllegalArgumentException("Session name must fit in 249 UTF-16 units and UTF-8 bytes");
        }
        if (name.endsWith(" ") || name.endsWith(".")) {
            throw new IllegalArgumentException("Session name must not end with a space or period");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isISOControl(character) || "/\\<>:\"|?*".indexOf(character) >= 0) {
                throw new IllegalArgumentException("Session name contains a forbidden character");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(++index))) {
                    throw new IllegalArgumentException("Session name contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Session name contains invalid Unicode");
            }
        }
        String stem = name.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³]")) {
            throw new IllegalArgumentException("Session name is a reserved Windows device name");
        }
    }
}
