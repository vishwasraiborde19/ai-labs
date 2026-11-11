package com.ailabs.devops.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.devops.core.Resources;
import com.ailabs.devops.health.HealthChecker;
import com.ailabs.devops.jenkins.JenkinsClient;
import com.ailabs.devops.jenkins.JenkinsTools;
import com.ailabs.devops.jenkins.JenkinsXml;
import com.ailabs.devops.kubernetes.KubeClient;
import com.fasterxml.jackson.databind.JsonNode;

/** Skills: build a pipeline, run it end to end, verify a release. */
@Component
public class PipelineTools {

    private static final String J = "Jenkins server name from config; omit for the default";

    private final Resources resources;
    private final HealthChecker health;

    public PipelineTools(Resources resources, HealthChecker health) {
        this.resources = resources;
        this.health = health;
    }

    @Tool(name = "pipeline_generate_jenkinsfile", description = "Generate a declarative Jenkinsfile that checks out the repo, "
            + "builds and tests it, builds and pushes a Docker image (tag BUILD_NUMBER-gitsha), deploys it to Kubernetes, "
            + "runs an HTTP health check and rolls back on failure. Returns the Jenkinsfile text; nothing is changed.")
    public String generate(@ToolParam(description = "Pipeline definition") PipelineSpec spec) {
        return JenkinsfileGenerator.generate(spec);
    }

    @Tool(name = "pipeline_create_job", description = "Create or update a Jenkins pipeline job from a pipeline definition. "
            + "The generated Jenkinsfile is stored inline in the job and runs in the Groovy sandbox.")
    public Map<String, Object> createJob(@ToolParam(required = false, description = J) String jenkins,
                                         @ToolParam(description = "Job name; use / for folders (the folder must exist)") String job,
                                         @ToolParam(description = "Pipeline definition") PipelineSpec spec,
                                         @ToolParam(required = false, description = "Job description") String description) {
        resources.requireWritableJenkins(jenkins);
        String jenkinsfile = JenkinsfileGenerator.generate(spec);
        String result = resources.jenkins(jenkins).upsertJob(job,
                JenkinsXml.inlinePipeline(description != null ? description : "CI/CD for " + spec.image(), jenkinsfile));
        return Map.of("job", job, "result", result, "jenkinsfile", jenkinsfile);
    }

    @Tool(name = "pipeline_create_scm_job", description = "Create or update a Jenkins pipeline job that reads its Jenkinsfile "
            + "from the git repository (use after committing a Jenkinsfile, e.g. one from pipeline_generate_jenkinsfile).")
    public Map<String, Object> createScmJob(@ToolParam(required = false, description = J) String jenkins,
                                            @ToolParam(description = "Job name") String job,
                                            @ToolParam(description = "Git repository URL") String gitUrl,
                                            @ToolParam(required = false, description = "Branch, default main") String branch,
                                            @ToolParam(required = false, description = "Path of the Jenkinsfile, default Jenkinsfile") String scriptPath,
                                            @ToolParam(required = false, description = "Jenkins credential id for git") String gitCredentialsId,
                                            @ToolParam(required = false, description = "Job description") String description) {
        resources.requireWritableJenkins(jenkins);
        String xml = JenkinsXml.scmPipeline(description != null ? description : "CI/CD from " + gitUrl, gitUrl,
                branch == null || branch.isBlank() ? "main" : branch, gitCredentialsId,
                scriptPath == null || scriptPath.isBlank() ? "Jenkinsfile" : scriptPath);
        return Map.of("job", job, "result", resources.jenkins(jenkins).upsertJob(job, xml));
    }

    @Tool(name = "pipeline_run", description = "Run a pipeline job and wait for it to finish (up to timeoutSeconds). "
            + "Returns the result and stage summary, plus the end of the console log if it did not succeed.")
    public Map<String, Object> run(@ToolParam(required = false, description = J) String jenkins,
                                   @ToolParam(description = "Job name") String job,
                                   @ToolParam(required = false, description = "Build parameters as name -> value") Map<String, String> parameters,
                                   @ToolParam(required = false, description = "Max seconds to wait, default 900, max 3600") Integer timeoutSeconds) {
        resources.requireWritableJenkins(jenkins);
        JenkinsClient c = resources.jenkins(jenkins);
        Duration timeout = Duration.ofSeconds(timeoutSeconds == null ? 900 : Math.max(30, Math.min(timeoutSeconds, 3600)));
        Instant start = Instant.now();
        long queueId = c.trigger(job, parameters);
        int n = c.awaitStart(queueId, timeout);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job);
        out.put("queueId", queueId);
        if (n < 0) {
            out.put("state", "still queued after " + timeout.toSeconds() + "s (check executors/agent labels)");
            return out;
        }
        JsonNode b = c.awaitFinish(job, n, timeout.minus(Duration.between(start, Instant.now())));
        out.putAll(JenkinsTools.describeBuild(c, job, n, b));
        boolean building = b.path("building").asBoolean();
        String result = b.path("result").asText("");
        if (building) {
            out.put("state", "still running; poll jenkins_build_status");
        } else if (!"SUCCESS".equals(result)) {
            out.put("consoleTail", c.consoleTail(job, n, 120, 20_000));
        }
        return out;
    }

    @Tool(name = "release_verify", description = "Verify a release on Kubernetes: rollout complete, all pods ready, the "
            + "expected image running, and optionally an HTTP health check. Read-only.")
    public Map<String, Object> verify(@ToolParam(required = false, description = "Cluster name from config; omit for the default") String cluster,
                                      @ToolParam(description = "Namespace") String namespace,
                                      @ToolParam(description = "Deployment name") String deployment,
                                      @ToolParam(required = false, description = "Image (repo:tag) that should be running") String expectedImage,
                                      @ToolParam(required = false, description = "Health URL to probe") String healthUrl,
                                      @ToolParam(required = false, description = "Seconds to wait for the rollout, default 120") Integer waitSeconds) {
        resources.requireNamespace(cluster, namespace, false);
        KubeClient k = resources.kube(cluster);
        Map<String, Object> status = k.awaitRollout(namespace, deployment,
                Duration.ofSeconds(waitSeconds == null ? 120 : Math.max(0, Math.min(waitSeconds, 900))));
        var pods = k.deploymentPods(namespace, deployment);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deployment", status);
        out.put("pods", pods);
        boolean ok = "complete".equals(status.get("rollout"));
        if (expectedImage != null && !expectedImage.isBlank()) {
            @SuppressWarnings("unchecked")
            boolean match = ((Map<String, String>) status.get("images")).containsValue(expectedImage);
            out.put("imageMatches", match);
            ok &= match;
        }
        if (healthUrl != null && !healthUrl.isBlank()) {
            Map<String, Object> h = health.check(healthUrl, 200, null, 5, 5, 5, false);
            out.put("health", h);
            ok &= Boolean.TRUE.equals(h.get("healthy"));
        }
        out.put("verdict", ok ? "HEALTHY" : "NOT HEALTHY");
        return out;
    }
}
