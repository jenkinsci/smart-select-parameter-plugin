package io.jenkins.plugins.smartselect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.StringParameterValue;
import java.util.List;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.StaplerRequest2;

public class SmartSelectParameterDefinitionTest {
    private SmartSelectParameterDefinition configured() {
        SmartSelectParameterDefinition parameter = new SmartSelectParameterDefinition("SERVICES");
        parameter.setChoices(List.of("price", "vehicle", "admin"));
        return parameter;
    }

    @Test
    public void defaultAndFormSubmission() {
        SmartSelectParameterDefinition parameter = configured();
        parameter.setDefaultValues(List.of("price", "vehicle"));
        assertEquals("price,vehicle", parameter.getDefaultParameterValue().getValue());
        assertEquals(
                "vehicle,admin",
                ((StringParameterValue) parameter.createValue(
                                (StaplerRequest2) null, JSONObject.fromObject("{\"value\":\"vehicle,admin\"}")))
                        .getValue());
    }

    @Test
    public void supportsSingleModeAndDefaultFirst() {
        SmartSelectParameterDefinition parameter = configured();
        parameter.setMode("SINGLE");
        assertEquals("price", parameter.getDefaultParameterValue().getValue());
        assertThrows(IllegalArgumentException.class, () -> parameter.createValue("price,vehicle"));
    }

    @Test
    public void rejectsTamperingAndTracksValidation() {
        SmartSelectParameterDefinition parameter = configured();
        assertTrue(parameter.isValid(new StringParameterValue("SERVICES", "price,vehicle")));
        assertFalse(parameter.isValid(new StringParameterValue("SERVICES", "unauthorized")));
        assertThrows(IllegalArgumentException.class, () -> parameter.createValue("unauthorized"));
    }

    @Test
    public void keepDefaultsOnCopyForRebuild() {
        SmartSelectParameterDefinition parameter = configured();
        SmartSelectParameterDefinition copy = (SmartSelectParameterDefinition)
                parameter.copyWithDefaultValue(new StringParameterValue("SERVICES", "vehicle,admin"));
        assertEquals("vehicle,admin", copy.getDefaultValueForBuild());
    }

    @Test
    public void pipelineBuildStepAcceptsDependentOptionsWithoutParentContext() {
        SmartSelectParameterDefinition parameter = new SmartSelectParameterDefinition("NAMESPACE");
        parameter.setMode("SINGLE");
        parameter.setDependsOn("CLUSTER");
        parameter.setDependentOptions(java.util.Map.of(
                "pmi-devops-eks",
                java.util.List.of(
                        java.util.Map.of("value", "byosphere-test1", "label", "byosphere-test1"),
                        java.util.Map.of("value", "byosphere-test2", "label", "byosphere-test2")),
                "pmi-ci-eks",
                java.util.List.of(java.util.Map.of("value", "byosphere-ci", "label", "byosphere-ci"))));

        assertEquals("byosphere-test1", parameter.createValue("byosphere-test1").getValue());
        assertTrue(parameter.isValid(new StringParameterValue("NAMESPACE", "byosphere-ci")));
        assertThrows(IllegalArgumentException.class, () -> parameter.createValue("missing-ns"));
        assertFalse(parameter.isValid(new StringParameterValue("NAMESPACE", "missing-ns")));
    }

    @Test
    public void pipelineBuildStepAcceptsStaticDependsOnWithoutParentContext() {
        SmartSelectParameterDefinition parameter = new SmartSelectParameterDefinition("REGION");
        parameter.setMode("SINGLE");
        parameter.setDependsOn("ENV");
        parameter.setChoices(java.util.List.of("us-east", "eu-west"));

        assertEquals("us-east", parameter.createValue("us-east").getValue());
        assertThrows(IllegalArgumentException.class, () -> parameter.createValue("ap-south"));
    }

    @Test
    public void pipelineBuildStepStillRequiresParentsForRestCascade() {
        SmartSelectParameterDefinition parameter = new SmartSelectParameterDefinition("SERVICES");
        parameter.setMode("SINGLE");
        parameter.setDependsOn("ENV");
        parameter.setRestUrl("https://services.example.com/options?env=${ENV}");

        assertThrows(IllegalArgumentException.class, () -> parameter.createValue("price"));
    }
}
