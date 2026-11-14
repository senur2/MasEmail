package fr.mas.email.contract;

import java.util.List;
import java.util.Objects;

/** Structural invariants shared by immutable contracts, including Jackson construction. */
public final class Checks {
    private Checks() {}

    public static String nullableText(String value, String field) {
        return value == null ? null : text(value, field);
    }

    public static void rule(boolean valid) {
        if (!valid) throw new InvalidOutputException();
    }

    public static void rule(boolean valid, String reason) {
        if (!valid) throw new InvalidOutputException(reason);
    }

    public static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, field + " is required");
    }

    public static <T> List<T> list(List<T> values, String field) {
        return List.copyOf(required(values, field));
    }

    public static List<String> texts(List<String> values, String field) {
        var copy = list(values, field);
        copy.forEach(value -> text(value, field + " entry"));
        return copy;
    }
}
