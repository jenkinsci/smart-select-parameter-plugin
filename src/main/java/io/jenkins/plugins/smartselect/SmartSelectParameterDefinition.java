package io.jenkins.plugins.smartselect;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.Secret;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

/** A build parameter that selects zero or more known choices in a searchable dropdown. */
public class SmartSelectParameterDefinition extends ParameterDefinition {
    private static final long serialVersionUID = 1L;
    private List<String> choices = new ArrayList<>();
    private List<String> defaultValues = new ArrayList<>();
    private String mode = "MULTI";
    private boolean searchable = true;
    private List<SelectOption> options = new ArrayList<>();
    private String restUrl = "";
    private String restItemsPath = "";
    private Secret restToken;
    private int cacheSeconds = 30;
    private String dependsOn = "";
    private String dependentOptions = "";
    private transient volatile RestSnapshot restSnapshot;

    private record RestSnapshot(String url, List<SelectOption> options, long expiresAt) {}

    public String getDependsOn() {
        return dependsOn == null ? "" : dependsOn;
    }

    @DataBoundSetter
    public void setDependsOn(String value) {
        String normalized = value == null ? "" : value.trim();
        List<String> names = java.util.Arrays.stream(normalized.split("[,\\r\\n]+"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        if (names.size() > 8
                || names.stream()
                        .anyMatch(name ->
                                name.equals(getName()) || !name.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N}_.-]{0,127}")))
            throw new IllegalArgumentException(Messages.DependenciesInvalid());
        dependsOn = String.join(",", names);
        restSnapshot = null;
    }

    public List<String> getDependencies() {
        return getDependsOn().isEmpty() ? List.of() : List.of(getDependsOn().split(","));
    }

    public String getDependentOptions() {
        return dependentOptions == null ? "" : dependentOptions;
    }

    @DataBoundSetter
    public void setDependentOptions(Object input) {
        if (input == null || (input instanceof String s && s.isBlank())) {
            dependentOptions = "";
            return;
        }
        dependentOptions = DependentOptionsCodec.normalize(input);
    }

    public boolean hasDependentOptions() {
        return !getDependentOptions().isBlank();
    }

    Map<String, List<SelectOption>> getParsedDependentOptions() {
        return DependentOptionsCodec.parse(getDependentOptions());
    }

    public List<SelectOption> getOptionsFor(Job<?, ?> job, java.util.Map<String, String> submitted) {
        return CascadeResolver.resolve(this, job, submitted);
    }

    String defaultFor(List<SelectOption> resolved) {
        List<String> allowed = resolved.stream().map(SelectOption::getValue).toList();
        if (getDependencies().isEmpty()) return SelectionCodec.join(effectiveDefaults(allowed));
        List<String> defaults = defaultValues.stream().filter(allowed::contains).toList();
        if (!isMulti() && defaults.size() > 1) throw new IllegalArgumentException(Messages.SingleDefaults());
        if (defaults.isEmpty() && !isMulti() && !allowed.isEmpty()) defaults = List.of(allowed.get(0));
        return SelectionCodec.join(defaults);
    }

    public String getRestUrl() {
        return restUrl == null ? "" : restUrl;
    }

    @DataBoundSetter
    public void setRestUrl(String value) {
        restUrl = value == null ? "" : value.trim();
        restSnapshot = null;
    }

    public String getRestItemsPath() {
        return restItemsPath == null ? "" : restItemsPath;
    }

    @DataBoundSetter
    public void setRestItemsPath(String value) {
        restItemsPath = value == null ? "" : value.trim();
        restSnapshot = null;
    }

    public Secret getRestToken() {
        return restToken == null ? Secret.fromString("") : restToken;
    }

    @DataBoundSetter
    public void setRestToken(String value) {
        restToken = Secret.fromString(value);
        restSnapshot = null;
    }

    public int getCacheSeconds() {
        return cacheSeconds;
    }

    @DataBoundSetter
    public void setCacheSeconds(int value) {
        if (value < 0 || value > 300)
            throw new IllegalArgumentException("Cache duration must be between 0 and 300 seconds");
        cacheSeconds = value;
        restSnapshot = null;
    }

    public boolean isRemote() {
        return !getRestUrl().isEmpty();
    }

    private synchronized void clearRestCache() {
        restSnapshot = null;
    }

    synchronized List<SelectOption> remoteOptions(String url) {
        DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
        RestOptionSource.validate(url, descriptor.getAllowedRestOrigins());
        RestSnapshot cached = restSnapshot;
        if (cached != null && cached.url().equals(url) && System.nanoTime() < cached.expiresAt())
            return cached.options();
        List<SelectOption> loaded = RestOptionSource.load(
                url, getRestItemsPath(), getRestToken().getPlainText(), descriptor.getAllowedRestOrigins());
        restSnapshot = new RestSnapshot(
                url, loaded, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(cacheSeconds));
        return loaded;
    }

    public static final class OptionView {
        private final List<SelectOption> options;
        private final String defaultValue;
        private final String error;
        private final java.util.Set<String> selected;

        OptionView(List<SelectOption> options, String defaultValue, String error) {
            this.options = options;
            this.defaultValue = defaultValue;
            this.error = error;
            this.selected = new java.util.HashSet<>(SelectionCodec.defaultsFrom(defaultValue));
        }

        public List<SelectOption> getOptions() {
            return options;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public String getError() {
            return error;
        }

        public String getOptionsJson() {
            return OptionCodec.toJson(options);
        }

        public boolean isSelected(String value) {
            return selected.contains(value);
        }
    }

    public OptionView getView() {
        if (!getDependencies().isEmpty()) return new OptionView(List.of(), "", "");
        try {
            List<SelectOption> resolved = getResolvedOptions();
            String defaults = SelectionCodec.join(effectiveDefaults(
                    resolved.stream().map(SelectOption::getValue).toList()));
            return new OptionView(resolved, defaults, "");
        } catch (IllegalArgumentException e) {
            return new OptionView(List.of(), "", Messages.ViewUnavailable());
        }
    }

    public List<SelectOption> getOptions() {
        return options == null ? List.of() : List.copyOf(options);
    }

    @DataBoundSetter
    public void setOptions(Object input) {
        options = new ArrayList<>(OptionCodec.parse(input));
    }

    public String getOptionsText() {
        return getOptions().isEmpty() ? "" : OptionCodec.toJson(getOptions());
    }

    public List<SelectOption> getResolvedOptions() {
        if (!getDependencies().isEmpty()) return List.of();
        if (isRemote()) return remoteOptions(getRestUrl());
        return getOptions().isEmpty()
                ? choices.stream().map(value -> new SelectOption(value, value)).toList()
                : getOptions();
    }

    private List<String> allowedValues() {
        return getResolvedOptions().stream().map(SelectOption::getValue).toList();
    }

    @DataBoundConstructor
    public SmartSelectParameterDefinition(String name) {
        super(name);
    }

    public List<String> getChoices() {
        return List.copyOf(choices);
    }

    /** A list works in Jenkinsfile; textarea string works in the job config UI. */
    @DataBoundSetter
    public void setChoices(Object choices) {
        this.choices = new ArrayList<>(SelectionCodec.choicesFrom(choices));
    }

    public String getChoicesText() {
        return String.join("\n", choices);
    }

    public List<String> getDefaultValues() {
        return List.copyOf(defaultValues);
    }

    @DataBoundSetter
    public void setDefaultValues(Object defaults) {
        this.defaultValues = new ArrayList<>(SelectionCodec.defaultsFrom(defaults));
    }

    public String getDefaultValuesText() {
        return SelectionCodec.join(defaultValues);
    }

    public String getMode() {
        return mode;
    }

    @DataBoundSetter
    public void setMode(String mode) {
        if (!"SINGLE".equals(mode) && !"MULTI".equals(mode)) {
            throw new IllegalArgumentException("mode must be SINGLE or MULTI");
        }
        this.mode = mode;
    }

    public boolean isSearchable() {
        return searchable;
    }

    @DataBoundSetter
    public void setSearchable(boolean searchable) {
        this.searchable = searchable;
    }

    public boolean isMulti() {
        return "MULTI".equals(mode);
    }

    private List<String> effectiveDefaults() {
        return effectiveDefaults(allowedValues());
    }

    private List<String> effectiveDefaults(List<String> choices) {
        if (choices.isEmpty()) {
            return List.of();
        }
        if (defaultValues.isEmpty()) {
            // As with a native choice parameter, SINGLE initially selects the first option.
            return isMulti() ? List.of() : List.of(choices.get(0));
        }
        return SelectionCodec.selectedFrom(SelectionCodec.join(defaultValues), choices, isMulti());
    }

    public String getDefaultValueForBuild() {
        return SelectionCodec.join(effectiveDefaults());
    }

    public boolean isInitiallySelected(String choice) {
        return effectiveDefaults().contains(choice);
    }

    @Override
    public StringParameterValue getDefaultParameterValue() {
        if (!getDependencies().isEmpty()) return new StringParameterValue(getName(), "", getDescription());
        if (allowedValues().isEmpty()) {
            return null;
        }
        return new StringParameterValue(getName(), getDefaultValueForBuild(), getDescription());
    }

    public StringParameterValue createValue(String submitted) {
        if (!getDependencies().isEmpty()) throw new IllegalArgumentException(Messages.ParentsRequired());
        List<String> values = SelectionCodec.selectedFrom(submitted, allowedValues(), isMulti());
        return new StringParameterValue(getName(), SelectionCodec.join(values), getDescription());
    }

    /** HTML form submission: the UI stores comma-delimited results in a hidden value field. */
    @Override
    public ParameterValue createValue(StaplerRequest2 req, JSONObject formData) {
        Object value = formData.opt("value");
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("Parameter value must be a string");
        }
        if (getDependencies().isEmpty()) return createValue(value == null ? "" : (String) value);
        java.util.Map<String, String> submitted = new java.util.HashMap<>();
        try {
            Object root = req.getSubmittedForm().get("parameter");
            java.util.Collection<?> entries = root instanceof net.sf.json.JSONArray array ? array : List.of(root);
            for (Object entry : entries) {
                if (!(entry instanceof JSONObject object) || !(object.get("name") instanceof String name))
                    throw new IllegalArgumentException(Messages.FormInvalid());
                Object rawValue = object.get("value");
                if (rawValue instanceof String || rawValue instanceof Boolean || rawValue instanceof Number) {
                    if (submitted.putIfAbsent(name, rawValue.toString()) != null)
                        throw new IllegalArgumentException(Messages.ParameterDuplicate());
                }
            }
        } catch (jakarta.servlet.ServletException e) {
            throw new IllegalArgumentException(Messages.FormRead());
        }
        return contextualValue(req.findAncestorObject(Job.class), submitted, value == null ? "" : (String) value);
    }

    private StringParameterValue contextualValue(Job<?, ?> job, java.util.Map<String, String> submitted, String value) {
        List<SelectOption> resolved = getOptionsFor(job, submitted);
        String selected = value == null ? defaultFor(resolved) : value;
        List<String> checked = SelectionCodec.selectedFrom(
                selected, resolved.stream().map(SelectOption::getValue).toList(), isMulti());
        return new StringParameterValue(getName(), SelectionCodec.join(checked), getDescription());
    }

    @Override
    public StringParameterValue createValue(StaplerRequest2 request) {
        String[] values = request.getParameterValues(getName());
        if (values != null && values.length != 1) throw new IllegalArgumentException(Messages.ParameterDuplicate());
        if (getDependencies().isEmpty()) return values == null ? getDefaultParameterValue() : createValue(values[0]);
        Job<?, ?> job = request.findAncestorObject(Job.class);
        java.util.Map<String, String> submitted = new java.util.HashMap<>();
        ParametersDefinitionProperty property =
                job == null ? null : job.getProperty(ParametersDefinitionProperty.class);
        if (property != null)
            for (ParameterDefinition definition : property.getParameterDefinitions()) {
                String[] raw = request.getParameterValues(definition.getName());
                if (raw != null) {
                    if (raw.length != 1) throw new IllegalArgumentException(Messages.ParameterDuplicate());
                    submitted.put(definition.getName(), raw[0]);
                }
            }
        return contextualValue(job, submitted, values == null ? null : values[0]);
    }

    @Override
    public StringParameterValue createValue(hudson.cli.CLICommand command, String value) {
        if (getDependencies().isEmpty()) return createValue(value);
        if (!(command instanceof hudson.cli.BuildCommand build))
            throw new IllegalArgumentException(Messages.CliBuildOnly());
        return contextualValue(build.job, build.parameters == null ? java.util.Map.of() : build.parameters, value);
    }

    @Override
    public boolean isValid(ParameterValue value) {
        if (!(value instanceof StringParameterValue)) {
            return false;
        }
        try {
            SelectionCodec.selectedFrom(((StringParameterValue) value).getValue(), allowedValues(), isMulti());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public ParameterDefinition copyWithDefaultValue(ParameterValue value) {
        if (!(value instanceof StringParameterValue) || !isValid(value)) {
            return this;
        }
        SmartSelectParameterDefinition copy = new SmartSelectParameterDefinition(getName());
        copy.setDescription(getDescription());
        copy.setChoices(choices);
        copy.setOptions(getOptions().stream()
                .map(option -> java.util.Map.of("value", option.getValue(), "label", option.getLabel()))
                .toList());
        copy.setMode(mode);
        copy.setSearchable(searchable);
        copy.setRestUrl(getRestUrl());
        copy.setRestItemsPath(getRestItemsPath());
        copy.setRestToken(getRestToken().getEncryptedValue());
        copy.setCacheSeconds(cacheSeconds);
        copy.setDependsOn(getDependsOn());
        if (hasDependentOptions()) copy.setDependentOptions(getDependentOptions());
        copy.setDefaultValues(((StringParameterValue) value).getValue());
        return copy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getName(),
                getDescription(),
                choices,
                defaultValues,
                mode,
                searchable,
                getOptions(),
                getRestUrl(),
                getRestItemsPath(),
                cacheSeconds,
                getDependsOn(),
                getDependentOptions());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SmartSelectParameterDefinition)) return false;
        SmartSelectParameterDefinition other = (SmartSelectParameterDefinition) obj;
        return Objects.equals(getName(), other.getName())
                && Objects.equals(getDescription(), other.getDescription())
                && Objects.equals(choices, other.choices)
                && Objects.equals(defaultValues, other.defaultValues)
                && Objects.equals(mode, other.mode)
                && searchable == other.searchable
                && Objects.equals(getOptions(), other.getOptions())
                && Objects.equals(getRestUrl(), other.getRestUrl())
                && Objects.equals(getRestItemsPath(), other.getRestItemsPath())
                && Objects.equals(
                        getRestToken().getPlainText(), other.getRestToken().getPlainText())
                && cacheSeconds == other.cacheSeconds
                && Objects.equals(getDependsOn(), other.getDependsOn())
                && Objects.equals(getDependentOptions(), other.getDependentOptions());
    }

    @Extension
    @Symbol("smartSelect")
    public static class DescriptorImpl extends ParameterDescriptor {
        private String allowedRestOrigins = "";

        public DescriptorImpl() {
            load();
        }

        public String getAllowedRestOrigins() {
            return allowedRestOrigins == null ? "" : allowedRestOrigins;
        }

        public void setAllowedRestOrigins(String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            String normalized = value == null ? "" : value.trim();
            for (String line : normalized.split("\\R")) {
                if (line.isBlank()) continue;
                java.net.URI uri = java.net.URI.create(line.trim());
                RestOptionSource.origin(uri);
                if (uri.getRawQuery() != null
                        || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                    throw new IllegalArgumentException(Messages.OriginFormat());
            }
            allowedRestOrigins = normalized;
        }

        @Override
        public boolean configure(StaplerRequest2 request, JSONObject json) throws FormException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                setAllowedRestOrigins(json.optString("allowedRestOrigins", ""));
            } catch (IllegalArgumentException e) {
                throw new FormException(Messages.OriginsInvalid(), "allowedRestOrigins");
            }
            save();
            return true;
        }

        @RequirePOST
        public HttpResponse doOptions(
                @AncestorInPath Job<?, ?> job,
                @QueryParameter String name,
                @QueryParameter String parents,
                @QueryParameter boolean refresh) {
            if (job == null) throw new IllegalArgumentException("Job is required");
            job.checkPermission(Item.BUILD);
            ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
            ParameterDefinition definition = property == null ? null : property.getParameterDefinition(name);
            JSONObject result = new JSONObject();
            if (!(definition instanceof SmartSelectParameterDefinition parameter))
                throw new IllegalArgumentException("Unknown smart select parameter");
            try {
                if (refresh) parameter.clearRestCache();
                java.util.Map<String, String> submitted = new java.util.HashMap<>();
                if (parents != null && !parents.isBlank()) {
                    if (parents.length() > 32768) throw new IllegalArgumentException(Messages.ParentsLength());
                    JSONObject values = JSONObject.fromObject(parents);
                    if (values.size() > 32) throw new IllegalArgumentException(Messages.ParentsCount());
                    for (Object rawEntry : values.entrySet()) {
                        java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) rawEntry;
                        Object value = entry.getValue();
                        if (!(value instanceof String text) || text.length() > 2048)
                            throw new IllegalArgumentException(Messages.ParentsFormat());
                        submitted.put(entry.getKey().toString(), text);
                    }
                }
                List<SelectOption> resolved = parameter.getOptionsFor(job, submitted);
                result.put("options", net.sf.json.JSONArray.fromObject(OptionCodec.toJson(resolved)));
                result.put("defaultValue", parameter.defaultFor(resolved));
            } catch (IllegalArgumentException e) {
                result.put("error", Messages.OptionsUnavailable());
            }
            return HttpResponses.okJSON(result);
        }

        @Override
        public String getDisplayName() {
            return Messages.DisplayName();
        }

        @POST
        public FormValidation doCheckChoices(@AncestorInPath Item item, @QueryParameter String value) {
            if (item == null) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            } else {
                item.checkPermission(Item.CONFIGURE);
            }
            try {
                List<String> parsed = SelectionCodec.choicesFrom(value);
                return parsed.isEmpty() ? FormValidation.error(Messages.ChoicesRequired()) : FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
