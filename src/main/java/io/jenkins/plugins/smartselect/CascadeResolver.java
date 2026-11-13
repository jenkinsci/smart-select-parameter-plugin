package io.jenkins.plugins.smartselect;

import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.SimpleParameterDefinition;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves dependency values from the submitted build and validates every parent before fetching. */
final class CascadeResolver {
    private CascadeResolver() {}

    static List<SelectOption> resolve(
            SmartSelectParameterDefinition parameter, Job<?, ?> job, Map<String, String> submitted) {
        return resolve(parameter, job, submitted, new HashSet<>());
    }

    private static List<SelectOption> resolve(
            SmartSelectParameterDefinition parameter, Job<?, ?> job, Map<String, String> submitted, Set<String> path) {
        if (parameter.getDependencies().isEmpty()) return parameter.getResolvedOptions();

        // Static list: no REST URL and no map — return static choices/options as-is regardless of parent value.
        if (!parameter.isRemote() && !parameter.hasDependentOptions()) {
            return parameter.getOptions().isEmpty()
                    ? parameter.getChoices().stream()
                            .map(v -> new SelectOption(v, v))
                            .toList()
                    : parameter.getOptions();
        }

        if (path.size() >= 16 || !path.add(parameter.getName()))
            throw new IllegalArgumentException(Messages.CascadeCycle());
        try {
            // Map-based: resolve and validate each parent value, then look up the compound key.
            if (parameter.hasDependentOptions()) {
                if (job == null) throw new IllegalArgumentException(Messages.CascadeContext());
                ParametersDefinitionProperty mapProperty = job.getProperty(ParametersDefinitionProperty.class);
                List<String> keyParts = new java.util.ArrayList<>();
                for (String name : parameter.getDependencies()) {
                    ParameterDefinition definition =
                            mapProperty == null ? null : mapProperty.getParameterDefinition(name);
                    String value;
                    if (definition instanceof SmartSelectParameterDefinition parent) {
                        if (parent.isMulti()) throw new IllegalArgumentException(Messages.DependentOptionsMultiParent());
                        List<SelectOption> parentOptions = resolve(parent, job, submitted, path);
                        value = submitted.containsKey(name) ? submitted.get(name) : parent.defaultFor(parentOptions);
                        value = SelectionCodec.join(SelectionCodec.selectedFrom(
                                value,
                                parentOptions.stream().map(SelectOption::getValue).toList(),
                                false));
                    } else if (definition instanceof SimpleParameterDefinition simple) {
                        ParameterValue checked = submitted.containsKey(name)
                                ? simple.createValue(submitted.get(name))
                                : simple.getDefaultParameterValue();
                        if (checked == null || !simple.isValid(checked))
                            throw new IllegalArgumentException(Messages.ParentInvalid());
                        if (!(checked.getValue() instanceof String
                                || checked.getValue() instanceof Boolean
                                || checked.getValue() instanceof Number))
                            throw new IllegalArgumentException(Messages.ParentSensitive());
                        value = String.valueOf(checked.getValue());
                        if (value.length() > 2048) throw new IllegalArgumentException(Messages.ParentLength());
                    } else throw new IllegalArgumentException(Messages.ParentMissing());
                    if (value == null || value.isBlank()) return List.of();
                    keyParts.add(value);
                }
                String key = String.join(",", keyParts);
                return parameter.getParsedDependentOptions().getOrDefault(key, List.of());
            }

            if (job == null || !parameter.isRemote()) throw new IllegalArgumentException(Messages.CascadeContext());
            ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
            String url = parameter.getRestUrl();
            int queryStart = url.indexOf('?');
            for (String name : parameter.getDependencies()) {
                String token = "${" + name + "}";
                if (queryStart < 0 || url.indexOf(token) <= queryStart)
                    throw new IllegalArgumentException(Messages.CascadePlaceholder());
                ParameterDefinition definition = property == null ? null : property.getParameterDefinition(name);
                String value;
                if (definition instanceof SmartSelectParameterDefinition parent) {
                    List<SelectOption> parentOptions = resolve(parent, job, submitted, path);
                    value = submitted.containsKey(name) ? submitted.get(name) : parent.defaultFor(parentOptions);
                    value = SelectionCodec.join(SelectionCodec.selectedFrom(
                            value,
                            parentOptions.stream().map(SelectOption::getValue).toList(),
                            parent.isMulti()));
                } else if (definition instanceof SimpleParameterDefinition simple) {
                    ParameterValue checked = submitted.containsKey(name)
                            ? simple.createValue(submitted.get(name))
                            : simple.getDefaultParameterValue();
                    if (checked == null || !simple.isValid(checked))
                        throw new IllegalArgumentException(Messages.ParentInvalid());
                    if (!(checked.getValue() instanceof String
                            || checked.getValue() instanceof Boolean
                            || checked.getValue() instanceof Number))
                        throw new IllegalArgumentException(Messages.ParentSensitive());
                    value = String.valueOf(checked.getValue());
                    if (value.length() > 2048) throw new IllegalArgumentException(Messages.ParentLength());
                } else throw new IllegalArgumentException(Messages.ParentMissing());
                if (value == null || value.isBlank()) return List.of();
                url = url.replace(token, URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
            if (url.contains("${")) throw new IllegalArgumentException(Messages.ParentUndeclared());
            return parameter.remoteOptions(url);
        } finally {
            path.remove(parameter.getName());
        }
    }
}
