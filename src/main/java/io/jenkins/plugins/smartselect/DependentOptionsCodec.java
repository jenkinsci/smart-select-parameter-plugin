package io.jenkins.plugins.smartselect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONObject;

/**
 * Parses and normalizes the {@code dependentOptions} map: a JSON object whose keys are
 * parent-value strings and whose values are option arrays accepted by {@link OptionCodec#parse}.
 *
 * <p>Single-parent key: the parent's value directly (e.g. {@code "dev"}).
 * Multi-parent key: comma-separated values in the same order as {@code dependsOn}
 * (e.g. {@code "dev,us-east"}).
 */
final class DependentOptionsCodec {
    private static final int MAX_ENTRIES = 2000;

    private DependentOptionsCodec() {}

    /**
     * Accepts a {@code String} (JSON object) or a {@code Map} (from Groovy DSL).
     * Validates all entries and returns a canonical JSON string for storage.
     */
    static String normalize(Object input) {
        Map<String, List<SelectOption>> parsed = parseObject(input);
        JSONObject result = new JSONObject();
        for (Map.Entry<String, List<SelectOption>> entry : parsed.entrySet()) {
            result.put(entry.getKey(), net.sf.json.JSONArray.fromObject(OptionCodec.toJson(entry.getValue())));
        }
        return result.toString();
    }

    /** Parses a JSON string produced by {@link #normalize} into a usable map. */
    static Map<String, List<SelectOption>> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        JSONObject root;
        try {
            root = JSONObject.fromObject(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(Messages.DependentOptionsInvalid());
        }
        Map<String, List<SelectOption>> result = new LinkedHashMap<>();
        for (Object rawKey : root.keySet()) {
            String key = rawKey.toString();
            Object value = root.get(key);
            result.put(key, OptionCodec.parse(value instanceof net.sf.json.JSONArray a ? a.toString() : value));
        }
        return result;
    }

    private static Map<String, List<SelectOption>> parseObject(Object input) {
        if (input instanceof String text) {
            if (!text.stripLeading().startsWith("{"))
                throw new IllegalArgumentException(Messages.DependentOptionsInvalid());
            JSONObject root;
            try {
                root = JSONObject.fromObject(text);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(Messages.DependentOptionsInvalid());
            }
            Map<String, List<SelectOption>> result = new LinkedHashMap<>();
            for (Object rawKey : root.keySet()) {
                String key = validateKey(rawKey.toString());
                Object value = root.get(key);
                result.put(key, OptionCodec.parse(value instanceof net.sf.json.JSONArray a ? a.toString() : value));
                if (result.size() > MAX_ENTRIES) throw new IllegalArgumentException(Messages.DependentOptionsInvalid());
            }
            return result;
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, List<SelectOption>> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = validateKey(entry.getKey().toString());
                result.put(key, OptionCodec.parse(entry.getValue()));
                if (result.size() > MAX_ENTRIES) throw new IllegalArgumentException(Messages.DependentOptionsInvalid());
            }
            return result;
        }
        throw new IllegalArgumentException(Messages.DependentOptionsInvalid());
    }

    private static String validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 2048)
            throw new IllegalArgumentException(Messages.DependentOptionsInvalid());
        return key;
    }
}
