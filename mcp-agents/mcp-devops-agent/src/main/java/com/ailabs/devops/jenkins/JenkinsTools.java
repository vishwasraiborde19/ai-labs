package com.ailabs.devops.jenkins;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.devops.access.SecretResolver;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.core.Resources;
import com.fasterxml.jackson.databind.JsonNode;

/** Skill: Jenkins access. */
@Component
public class JenkinsTools {

    static final String J = "Jenkins server name from config; omit for the default";

    private final Resources resources;
    private final SecretResolver secrets;

    public JenkinsTools(Resources resources, SecretResolver secrets) {
        this.resources = resources;
        this.secrets = secrets;
    }

    @Tool(name = "jenkins_list_jobs", description = "List jobs (and folders) on a Jenkins server with their last build result.")
    public JsonNode listJobs(@ToolParam(required = false, description = J) String jenkins,
                             @ToolParam(required = false, description = "Folder path, e.g. team/services") String folder) {
        return resources.jenkins(jenkins).listJobs(folder).path("jobs");
    }

    @Tool(name = "jenkins_job_info", description = "Describe a Jenkins job: status, last builds and build parameters.")
    public JsonNode jobInfo(@ToolParam(required = false, description = J) String jenkins,
                            @ToolParam(description = "Job name; use / for folders, e.g. team/my-app") String job) {
        return resources.jenkins(jenkins).jobInfo(job);
    }

    @Tool(name = "jenkins_get_job_config", description = "Return a job's config.xml (contains the inline Jenkinsfile for pipeline jobs).")
    public String jobConfig(@ToolParam(required = false, description = J) String jenkins,
                            @ToolParam(description = "Job name") String job) {
        return resources.jenkins(jenkins).jobConfig(job);
    }

    @Tool(name = "jenkins_trigger_build", description = "Queue a build of a Jenkins job, optionally with parameters. "
            + "Returns the queue id and, if it started within waitForStartSeconds, the build number.")
    public Map<String, Object> trigger(@ToolParam(required = false, description = J) String jenkins,
                                       @ToolParam(description = "Job name") String job,
                                       @ToolParam(required = false, description = "Build parameters as name -> value") Map<String, String> parameters,
                                       @ToolParam(required = false, description = "Seconds to wait for the build to leave the queue, default 30") Integer waitForStartSeconds) {
        resources.requireWritableJenkins(jenkins);
        JenkinsClient c = resources.jenkins(jenkins);
        long queueId = c.trigger(job, parameters);
        int wait = waitForStartSeconds == null ? 30 : Math.max(0, Math.min(waitForStartSeconds, 600));
        int number = wait == 0 ? -1 : c.awaitStart(queueId, Duration.ofSeconds(wait));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job);
        out.put("queueId", queueId);
        out.put("buildNumber", number > 0 ? number : null);
        out.put("state", number > 0 ? "started" : "queued");
        return out;
    }

    @Tool(name = "jenkins_build_status", description = "Status of a build: result, whether it is still running, duration and pipeline stages.")
    public Map<String, Object> buildStatus(@ToolParam(required = false, description = J) String jenkins,
                                           @ToolParam(description = "Job name") String job,
                                           @ToolParam(required = false, description = "Build number; omit for the last build") Integer buildNumber) {
        JenkinsClient c = resources.jenkins(jenkins);
        int n = buildNumber != null ? buildNumber : c.lastBuildNumber(job);
        return describeBuild(c, job, n, c.build(job, n));
    }

    @Tool(name = "jenkins_console_log", description = "Last lines of a build's console output. Use it to diagnose failures.")
    public String console(@ToolParam(required = false, description = J) String jenkins,
                          @ToolParam(description = "Job name") String job,
                          @ToolParam(required = false, description = "Build number; omit for the last build") Integer buildNumber,
                          @ToolParam(required = false, description = "Number of lines from the end, default 200") Integer tailLines) {
        JenkinsClient c = resources.jenkins(jenkins);
        int n = buildNumber != null ? buildNumber : c.lastBuildNumber(job);
        return c.consoleTail(job, n, tailLines != null ? Math.min(tailLines, 5000) : 200, 60_000);
    }

    @Tool(name = "jenkins_stop_build", description = "Abort a running build.")
    public Map<String, Object> stop(@ToolParam(required = false, description = J) String jenkins,
                                    @ToolParam(description = "Job name") String job,
                                    @ToolParam(description = "Build number") Integer buildNumber) {
        if (buildNumber == null) {
            throw new IllegalArgumentException("buildNumber is required");
        }
        resources.requireWritableJenkins(jenkins);
        resources.jenkins(jenkins).stop(job, buildNumber);
        return Map.of("job", job, "buildNumber", buildNumber, "state", "stop requested");
    }

    @Tool(name = "jenkins_sync_credential", description = "Copy a secret from the access server into the Jenkins credential "
            + "store so pipelines can use it by id. The secret value is never returned. Only refs listed in the Jenkins server's "
            + "credential-sync-allow config are permitted. kind is usernamePassword (needs username or usernameRef) or secretText.")
    public Map<String, Object> syncCredential(@ToolParam(required = false, description = J) String jenkins,
                                              @ToolParam(description = "Credential id to create or update in Jenkins, e.g. dockerhub") String credentialId,
                                              @ToolParam(description = "usernamePassword or secretText") String kind,
                                              @ToolParam(description = "Secret reference for the password/secret, e.g. access:dockerhub/ci/token") String secretRef,
                                              @ToolParam(required = false, description = "Username for usernamePassword credentials") String username,
                                              @ToolParam(required = false, description = "Secret reference for the username instead of a literal") String usernameRef,
                                              @ToolParam(required = false, description = "Description shown in Jenkins") String description) {
        resources.requireWritableJenkins(jenkins);
        JenkinsServer cfg = resources.jenkinsConfig(jenkins);
        if (!cfg.maySync(secretRef) || (usernameRef != null && !usernameRef.isBlank() && !cfg.maySync(usernameRef))) {
            throw new IllegalStateException("Secret ref not allowed for sync to Jenkins '" + cfg.name()
                    + "'. Allowed: " + cfg.credentialSyncAllow());
        }
        if (credentialId == null || !credentialId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("credentialId must match [A-Za-z0-9._-]{1,128}");
        }
        String purpose = "jenkins:" + cfg.name() + ":credential:" + credentialId;
        String desc = description != null ? description : "Managed by mcp-devops-agent from " + secretRef;
        String xml = switch (kind == null ? "" : kind) {
            case "usernamePassword" -> {
                String user = usernameRef != null && !usernameRef.isBlank() ? secrets.resolve(usernameRef, purpose) : username;
                if (user == null || user.isBlank()) {
                    throw new IllegalArgumentException("usernamePassword needs username or usernameRef");
                }
                yield JenkinsXml.usernamePassword(credentialId, desc, user, secrets.resolve(secretRef, purpose));
            }
            case "secretText" -> JenkinsXml.secretText(credentialId, desc, secrets.resolve(secretRef, purpose));
            default -> throw new IllegalArgumentException("kind must be usernamePassword or secretText");
        };
        String result = resources.jenkins(jenkins).upsertCredential(credentialId, xml);
        return Map.of("jenkins", cfg.name(), "credentialId", credentialId, "kind", kind, "result", result);
    }

    public static Map<String, Object> describeBuild(JenkinsClient c, String job, int n, JsonNode b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job);
        out.put("buildNumber", n);
        out.put("building", b.path("building").asBoolean());
        out.put("result", b.path("result").isNull() ? null : b.path("result").asText());
        out.put("durationSeconds", b.path("duration").asLong() / 1000);
        out.put("url", b.path("url").asText());
        var stages = c.stages(job, n);
        if (stages != null) {
            out.put("stages", stages);
        }
        return out;
    }
}
