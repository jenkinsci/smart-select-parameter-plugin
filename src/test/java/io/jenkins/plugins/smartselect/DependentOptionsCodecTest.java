package io.jenkins.plugins.smartselect;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependentOptionsCodecTest {

    // --- normalize (from Groovy Map / String) ---

    @Test
    void normalizesGroovyMapOfStringLists() {
        String json = DependentOptionsCodec.normalize(Map.of(
                "dev", List.of("svc-a", "svc-b"),
                "prod", List.of("svc-a")));
        Map<String, List<SelectOption>> parsed = DependentOptionsCodec.parse(json);
        assertEquals(List.of("svc-a", "svc-b"), values(parsed.get("dev")));
        assertEquals(List.of("svc-a"), values(parsed.get("prod")));
    }

    @Test
    void normalizesGroovyMapOfOptionMaps() {
        String json = DependentOptionsCodec.normalize(Map.of(
                "dev",
                List.of(
                        Map.of("value", "svc-a", "label", "Service A"),
                        Map.of("value", "svc-b", "label", "Service B"))));
        Map<String, List<SelectOption>> parsed = DependentOptionsCodec.parse(json);
        List<SelectOption> options = parsed.get("dev");
        assertEquals("svc-a", options.get(0).getValue());
        assertEquals("Service A", options.get(0).getLabel());
    }

    @Test
    void normalizesJsonString() {
        String input = "{\"dev\":[\"svc-a\",\"svc-b\"],\"prod\":[\"svc-a\"]}";
        String json = DependentOptionsCodec.normalize(input);
        Map<String, List<SelectOption>> parsed = DependentOptionsCodec.parse(json);
        assertEquals(List.of("svc-a", "svc-b"), values(parsed.get("dev")));
    }

    @Test
    void normalizesMultiParentKey() {
        String json = DependentOptionsCodec.normalize(Map.of(
                "dev,us-east", List.of("cluster-1"),
                "prod,us-east", List.of("cluster-2", "cluster-3")));
        Map<String, List<SelectOption>> parsed = DependentOptionsCodec.parse(json);
        assertEquals(List.of("cluster-1"), values(parsed.get("dev,us-east")));
        assertEquals(List.of("cluster-2", "cluster-3"), values(parsed.get("prod,us-east")));
    }

    @Test
    void roundtripsViaJson() {
        Map<String, Object> input = Map.of("dev", List.of("svc-a", "svc-b"), "prod", List.of("svc-a"));
        String json = DependentOptionsCodec.normalize(input);
        Map<String, List<SelectOption>> first = DependentOptionsCodec.parse(json);
        Map<String, List<SelectOption>> second = DependentOptionsCodec.parse(DependentOptionsCodec.normalize(json));
        assertEquals(values(first.get("dev")), values(second.get("dev")));
        assertEquals(values(first.get("prod")), values(second.get("prod")));
    }

    // --- parse edge cases ---

    @Test
    void parseReturnsEmptyMapForBlankInput() {
        assertEquals(Map.of(), DependentOptionsCodec.parse(null));
        assertEquals(Map.of(), DependentOptionsCodec.parse(""));
        assertEquals(Map.of(), DependentOptionsCodec.parse("   "));
    }

    @Test
    void parseHandlesEmptyOptionArray() {
        Map<String, List<SelectOption>> parsed = DependentOptionsCodec.parse("{\"dev\":[],\"prod\":[\"svc-a\"]}");
        assertTrue(parsed.get("dev").isEmpty());
        assertEquals(List.of("svc-a"), values(parsed.get("prod")));
    }

    // --- validation errors ---

    @Test
    void rejectsNonObjectJson() {
        assertThrows(IllegalArgumentException.class, () -> DependentOptionsCodec.normalize("[\"not-an-object\"]"));
    }

    @Test
    void rejectsBlankKey() {
        assertThrows(
                IllegalArgumentException.class, () -> DependentOptionsCodec.normalize(Map.of("", List.of("svc-a"))));
    }

    @Test
    void rejectsKeyExceedingMaxLength() {
        String longKey = "x".repeat(2049);
        assertThrows(
                IllegalArgumentException.class,
                () -> DependentOptionsCodec.normalize(Map.of(longKey, List.of("svc-a"))));
    }

    @Test
    void rejectsInvalidOptionValues() {
        // Options with commas are rejected by OptionCodec
        assertThrows(
                IllegalArgumentException.class,
                () -> DependentOptionsCodec.normalize(Map.of("dev", List.of("bad,value"))));
    }

    @Test
    void rejectsNonMapNonStringInput() {
        assertThrows(IllegalArgumentException.class, () -> DependentOptionsCodec.normalize(42));
        assertThrows(IllegalArgumentException.class, () -> DependentOptionsCodec.normalize(List.of("not-a-map")));
    }

    // --- integration with SmartSelectParameterDefinition ---

    @Test
    void setDependentOptionsAcceptsGroovyMapAndRoundtrips() {
        SmartSelectParameterDefinition param = new SmartSelectParameterDefinition("SERVICE");
        param.setDependentOptions(Map.of("dev", List.of("svc-a", "svc-b"), "prod", List.of("svc-a")));
        assertTrue(param.hasDependentOptions());
        Map<String, List<SelectOption>> parsed = param.getParsedDependentOptions();
        assertEquals(List.of("svc-a", "svc-b"), values(parsed.get("dev")));
        assertEquals(List.of("svc-a"), values(parsed.get("prod")));
    }

    @Test
    void setDependentOptionsAcceptsJsonString() {
        SmartSelectParameterDefinition param = new SmartSelectParameterDefinition("SERVICE");
        param.setDependentOptions("{\"dev\":[\"svc-a\"],\"prod\":[\"svc-a\",\"svc-c\"]}");
        assertTrue(param.hasDependentOptions());
        assertEquals(List.of("svc-a"), values(param.getParsedDependentOptions().get("dev")));
    }

    @Test
    void setDependentOptionsNullOrBlankClearsField() {
        SmartSelectParameterDefinition param = new SmartSelectParameterDefinition("SERVICE");
        param.setDependentOptions(Map.of("dev", List.of("svc-a")));
        assertTrue(param.hasDependentOptions());
        param.setDependentOptions(null);
        assertFalse(param.hasDependentOptions());
        param.setDependentOptions(Map.of("dev", List.of("svc-a")));
        param.setDependentOptions("   ");
        assertFalse(param.hasDependentOptions());
    }

    // --- helpers ---

    private static List<String> values(List<SelectOption> options) {
        return options.stream().map(SelectOption::getValue).toList();
    }
}
