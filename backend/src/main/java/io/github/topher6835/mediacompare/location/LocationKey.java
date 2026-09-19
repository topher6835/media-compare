package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Canonical validated text form of an lk1 location equality key. */
public final class LocationKey {
    private final String value;

    private LocationKey(String value) {
        this.value = value;
    }

    public static LocationKey parse(String value) {
        LocationKeyCodec.decode(value);
        return new LocationKey(value);
    }

    static LocationKey canonical(String value) {
        return new LocationKey(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof LocationKey key && value.equals(key.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
