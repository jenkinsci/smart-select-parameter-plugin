package io.jenkins.plugins.smartselect;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class CascadingJenkinsTest {
    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule instance) {
        this.jenkins = instance;
    }

    @Test
    public void dependenciesUseActualSubmittedParentsAcrossApiFormAndCli() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String value = exchange.getRequestURI().getRawQuery().equals("env=release") ? "api-release" : "api-dev";
            byte[] bytes = ("[{\"value\":\"" + value + "\",\"label\":\"服务\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            jenkins.jenkins
                    .getDescriptorByType(SmartSelectParameterDefinition.DescriptorImpl.class)
                    .setAllowedRestOrigins(origin);
            SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
            parent.setMode("SINGLE");
            parent.setChoices(List.of("dev", "release"));
            SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("SERVICES");
            child.setMode("SINGLE");
            child.setDependsOn("ENV");
            child.setRestUrl(origin + "/?env=${ENV}");
            FreeStyleProject project = jenkins.createFreeStyleProject();
            project.addProperty(new ParametersDefinitionProperty(child, parent)); // independent of parameter order
            assertEquals(
                    "api-release",
                    child.getOptionsFor(project, Map.of("ENV", "release"))
                            .get(0)
                            .getValue());
            assertThrows(IllegalArgumentException.class, () -> child.getOptionsFor(project, Map.of("ENV", "tampered")));
            try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
                request(
                        client,
                        project,
                        "buildWithParameters",
                        List.of(new NameValuePair("ENV", "release"), new NameValuePair("SERVICES", "api-release")));
                jenkins.waitUntilNoActivity();
                assertEquals(1, project.getLastBuild().getNumber());
                jenkins.assertBuildStatusSuccess(project.getLastBuild());
                client.getOptions().setThrowExceptionOnFailingStatusCode(false);
                int status = request(
                        client,
                        project,
                        "buildWithParameters",
                        List.of(new NameValuePair("ENV", "dev"), new NameValuePair("SERVICES", "api-release")));
                assertTrue(status >= 400);
                jenkins.waitUntilNoActivity();
                assertEquals(1, project.getLastBuild().getNumber());
                client.getOptions().setThrowExceptionOnFailingStatusCode(true);
                request(client, project, "buildWithParameters", List.of(new NameValuePair("ENV", "dev")));
                jenkins.waitUntilNoActivity();
                assertEquals(
                        "api-dev",
                        project.getLastBuild()
                                .getAction(ParametersAction.class)
                                .getParameter("SERVICES")
                                .getValue());
                request(
                        client,
                        project,
                        "build",
                        List.of(
                                new NameValuePair(
                                        "json",
                                        "{\"parameter\":[{\"name\":\"SERVICES\",\"value\":\"api-release\"},{\"name\":\"ENV\",\"value\":\"release\"}]}")));
                jenkins.waitUntilNoActivity();
                assertEquals(3, project.getLastBuild().getNumber());
                assertEquals(
                        "api-release",
                        project.getLastBuild()
                                .getAction(ParametersAction.class)
                                .getParameter("SERVICES")
                                .getValue());
            }
            CLICommandInvoker.Result cli = new CLICommandInvoker(jenkins, "build")
                    .invokeWithArgs(project.getName(), "-p", "ENV=release", "-p", "SERVICES=api-release", "-s");
            assertEquals(0, cli.returnCode(), cli.stderr());
            assertEquals(4, project.getLastBuild().getNumber());
            parent.setDependsOn("SERVICES");
            parent.setRestUrl(origin + "/?service=${SERVICES}");
            assertThrows(IllegalArgumentException.class, () -> child.getOptionsFor(project, Map.of("ENV", "release")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void staticListBranchReturnsChoicesRegardlessOfParentValue() throws Exception {
        SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
        parent.setMode("SINGLE");
        parent.setChoices(List.of("dev", "prod"));

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("REGION");
        child.setMode("SINGLE");
        child.setChoices(List.of("us-east", "eu-west"));
        child.setDependsOn("ENV");

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(parent, child));

        // Same options regardless of parent value
        List<SelectOption> forDev = child.getOptionsFor(project, Map.of("ENV", "dev"));
        List<SelectOption> forProd = child.getOptionsFor(project, Map.of("ENV", "prod"));
        assertEquals(
                List.of("us-east", "eu-west"),
                forDev.stream().map(SelectOption::getValue).toList());
        assertEquals(forDev, forProd);
    }

    @Test
    void staticListBranchAcceptsBuildSubmission() throws Exception {
        SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
        parent.setMode("SINGLE");
        parent.setChoices(List.of("dev", "prod"));

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("REGION");
        child.setMode("SINGLE");
        child.setChoices(List.of("us-east", "eu-west"));
        child.setDependsOn("ENV");

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(parent, child));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            request(
                    client,
                    project,
                    "buildWithParameters",
                    List.of(new NameValuePair("ENV", "dev"), new NameValuePair("REGION", "us-east")));
            jenkins.waitUntilNoActivity();
            jenkins.assertBuildStatusSuccess(project.getLastBuild());
            assertEquals(
                    "us-east",
                    project.getLastBuild()
                            .getAction(ParametersAction.class)
                            .getParameter("REGION")
                            .getValue());
        }
    }

    @Test
    void dependentOptionsBranchResolvesCorrectOptionsForParentValue() throws Exception {
        SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
        parent.setMode("SINGLE");
        parent.setChoices(List.of("dev", "prod"));

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("SERVICE");
        child.setMode("SINGLE");
        child.setDependsOn("ENV");
        child.setDependentOptions(Map.of(
                "dev", List.of("svc-a", "svc-b"),
                "prod", List.of("svc-a")));

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(parent, child));

        List<SelectOption> devOptions = child.getOptionsFor(project, Map.of("ENV", "dev"));
        List<SelectOption> prodOptions = child.getOptionsFor(project, Map.of("ENV", "prod"));
        assertEquals(
                List.of("svc-a", "svc-b"),
                devOptions.stream().map(SelectOption::getValue).toList());
        assertEquals(
                List.of("svc-a"),
                prodOptions.stream().map(SelectOption::getValue).toList());
    }

    @Test
    void dependentOptionsBranchReturnsEmptyForUnknownKey() throws Exception {
        SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
        parent.setMode("SINGLE");
        parent.setChoices(List.of("dev", "prod"));

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("SERVICE");
        child.setMode("SINGLE");
        child.setDependsOn("ENV");
        child.setDependentOptions(Map.of("dev", List.of("svc-a")));

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(parent, child));

        List<SelectOption> options = child.getOptionsFor(project, Map.of("ENV", "prod"));
        assertTrue(options.isEmpty());
    }

    @Test
    void dependentOptionsBranchUsesParentDefaultWhenNotSubmitted() throws Exception {
        SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
        parent.setMode("SINGLE");
        parent.setChoices(List.of("dev", "prod"));
        parent.setDefaultValues("prod");

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("SERVICE");
        child.setMode("SINGLE");
        child.setDependsOn("ENV");
        child.setDependentOptions(Map.of(
                "dev", List.of("svc-a", "svc-b"),
                "prod", List.of("svc-a")));

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(parent, child));

        // ENV not submitted → parent default "prod" is used → child gets prod options
        List<SelectOption> options = child.getOptionsFor(project, Map.of());
        assertEquals(List.of("svc-a"), options.stream().map(SelectOption::getValue).toList());
    }

    @Test
    void dependentOptionsBranchRejectsInvalidParentValue() throws Exception {
        SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
        parent.setMode("SINGLE");
        parent.setChoices(List.of("dev", "prod"));

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("SERVICE");
        child.setMode("SINGLE");
        child.setDependsOn("ENV");
        child.setDependentOptions(Map.of("dev", List.of("svc-a")));

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(parent, child));

        // "tampered" is not a valid option for ENV → rejected
        assertThrows(
                IllegalArgumentException.class, () -> child.getOptionsFor(project, Map.of("ENV", "tampered")));
    }

@Test
    void dependentOptionsBranchSupportsMultiParentKey() throws Exception {
        SmartSelectParameterDefinition env = new SmartSelectParameterDefinition("ENV");
        env.setMode("SINGLE");
        env.setChoices(List.of("dev", "prod"));

        SmartSelectParameterDefinition region = new SmartSelectParameterDefinition("REGION");
        region.setMode("SINGLE");
        region.setChoices(List.of("us-east", "eu-west"));

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("CLUSTER");
        child.setMode("SINGLE");
        child.setDependsOn("ENV,REGION");
        child.setDependentOptions(Map.of(
                "dev,us-east", List.of("cluster-dev-1"),
                "prod,us-east", List.of("cluster-prod-1", "cluster-prod-2")));

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(env, region, child));

        List<SelectOption> devUse1 = child.getOptionsFor(project, Map.of("ENV", "dev", "REGION", "us-east"));
        List<SelectOption> prodUse1 = child.getOptionsFor(project, Map.of("ENV", "prod", "REGION", "us-east"));
        List<SelectOption> devEuw1 = child.getOptionsFor(project, Map.of("ENV", "dev", "REGION", "eu-west"));

        assertEquals(
                List.of("cluster-dev-1"),
                devUse1.stream().map(SelectOption::getValue).toList());
        assertEquals(
                List.of("cluster-prod-1", "cluster-prod-2"),
                prodUse1.stream().map(SelectOption::getValue).toList());
        assertTrue(devEuw1.isEmpty());
    }

    @Test
    void dependentOptionsBranchAcceptsBuildSubmission() throws Exception {
        SmartSelectParameterDefinition parent = new SmartSelectParameterDefinition("ENV");
        parent.setMode("SINGLE");
        parent.setChoices(List.of("dev", "prod"));

        SmartSelectParameterDefinition child = new SmartSelectParameterDefinition("SERVICE");
        child.setMode("SINGLE");
        child.setDependsOn("ENV");
        child.setDependentOptions(Map.of("dev", List.of("svc-a", "svc-b"), "prod", List.of("svc-a")));

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.addProperty(new ParametersDefinitionProperty(parent, child));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            request(
                    client,
                    project,
                    "buildWithParameters",
                    List.of(new NameValuePair("ENV", "prod"), new NameValuePair("SERVICE", "svc-a")));
            jenkins.waitUntilNoActivity();
            jenkins.assertBuildStatusSuccess(project.getLastBuild());
            assertEquals(
                    "svc-a",
                    project.getLastBuild()
                            .getAction(ParametersAction.class)
                            .getParameter("SERVICE")
                            .getValue());

            // Value not in map for chosen parent → rejected
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            int status = request(
                    client,
                    project,
                    "buildWithParameters",
                    List.of(new NameValuePair("ENV", "prod"), new NameValuePair("SERVICE", "svc-b")));
            assertTrue(status >= 400);
        }
    }

    private int request(
            JenkinsRule.WebClient client, FreeStyleProject project, String action, List<NameValuePair> values)
            throws Exception {
        WebRequest request =
                new WebRequest(new URL(jenkins.getURL(), project.getUrl() + action + "?delay=0sec"), HttpMethod.POST);
        request.setRequestParameters(values);
        return client.getPage(client.addCrumb(request)).getWebResponse().getStatusCode();
    }
}
