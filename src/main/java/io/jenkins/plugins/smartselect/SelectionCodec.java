package io.jenkins.plugins.smartselect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless parsing/validation shared by form submits, API builds and Pipeline.
 * No Jenkins dependencies: easy to check independently and reuse in tests.
 * The v0.1 wire format is a comma-separated string, so a choice cannot contain a comma.
 */
public final class SelectionCodec {
    private static final int MAX_CHOICES = 2000;
    private static final int MAX_CHOICE_LENGTH = 256;

    private SelectionCodec() {}

    /** Accepts newline-separated textarea values or a Jenkinsfile {@code List<String>}. */
    public static List<String> choicesFrom(Object input) {
        Collection<?> entries;
        if (input == null) {
            entries = List.of();
        } else if (input instanceof String) {
            entries = Arrays.asList(((String) input).split("\\R", -1));
        } else if (input instanceof Collection<?>) {
            entries = (Collection<?>) input;
        } else {
            throw new IllegalArgumentException("choices must be a newline-separated string or a list");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (Object raw : entries) {
            if (raw == null) {
                continue;
            }
            String value = raw.toString().trim();
            if (value.isEmpty()) {
                continue;
            }
            checkChoice(value);
            distinct.add(value);
            if (distinct.size() > MAX_CHOICES) {
                throw new IllegalArgumentException("At most " + MAX_CHOICES + " choices are supported");
            }
        }
        return new ArrayList<>(distinct);
    }

    /** Default values from a comma-delimited config field or Jenkinsfile list. */
    public static List<String> defaultsFrom(Object input) {
        if (input == null) {
            return List.of();
        }
        Collection<?> entries;
        if (input instanceof String) {
            entries = Arrays.asList(((String) input).split(",", -1));
        } else if (input instanceof Collection<?>) {
            entries = (Collection<?>) input;
        } else {
            throw new IllegalArgumentException("defaultValues must be a comma-separated string or a list");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (Object raw : entries) {
            if (raw == null) {
                continue;
            }
            String value = raw.toString().trim();
            if (!value.isEmpty()) {
                checkChoice(value);
                distinct.add(value);
            }
        }
        return new ArrayList<>(distinct);
    }

    public static List<String> selectedFrom(String submitted, List<String> allowed, boolean multi) {
        if (submitted == null || submitted.isBlank()) {
            return List.of();
        }
        List<String> values = defaultsFrom(submitted);
        if (!multi && values.size() > 1) {
            throw new IllegalArgumentException("Single-select parameter cannot contain multiple values");
        }
        if (!allowed.containsAll(values)) {
            // Do not echo the untrusted submitted value into errors or job logs.
            throw new IllegalArgumentException("Submitted selection is not in the configured choices");
        }
        return values;
    }

    public static String join(List<String> values) {
        return String.join(",", values);
    }

    private static void checkChoice(String choice) {
        if (choice.length() > MAX_CHOICE_LENGTH) {
            throw new IllegalArgumentException("Each choice must be at most " + MAX_CHOICE_LENGTH + " characters");
        }
        if (choice.indexOf(',') >= 0 || choice.indexOf('\n') >= 0 || choice.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Choices cannot contain commas or line breaks in v0.1");
        }
    }
}
