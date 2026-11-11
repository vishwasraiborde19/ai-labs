package com.ailabs.devops.jenkins;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.http.HttpSupport;
import com.ailabs.devops.http.HttpSupport.Resp;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Jenkins REST API over basic auth with a user API token. API-token requests are exempt from CSRF
 * on current Jenkins; if a server still asks for a crumb we fetch one and retry once.
 */
public class JenkinsClient {

    private static final Pattern QUEUE_ID = Pattern.compile("/queue/item/(\\d+)");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final JenkinsServer cfg;
    private final Supplier<String> token;
    private final HttpClient http;
    private volatile String[] crumb;

    public JenkinsClient(JenkinsServer cfg, Supplier<String> token, String caPem) {
        this.cfg = cfg;
        this.token = token;
        this.http = HttpSupport.client(cfg.insecureSkipTlsVerify(), caPem, true, Duration.ofSeconds(10));
    }

    public String name() {
        return cfg.name();
    }

    /** {@code folder/sub/app} -> {@code /job/folder/job/sub/job/app}. */
    public static String jobPath(String job) {
        if (job == null || job.isBlank()) {
            throw new IllegalArgumentException("job name is required");
        }
        StringBuilder sb = new StringBuilder();
        for (String part : job.split("/")) {
            if (!part.isBlank()) {
                sb.append("/job/").append(HttpSupport.enc(part.trim()));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ reads

    public JsonNode whoAmI() {
        return getJson("/whoAmI/api/json");
    }

    public JsonNode version() {
        Resp r = get("/api/json?tree=mode,nodeDescription,useSecurity");
        if (!r.ok()) {
            throw HttpSupport.failure("Jenkins " + name() + " /api/json", r);
        }
        return HttpSupport.JSON.createObjectNode()
                .put("version", r.headers().firstValue("X-Jenkins").orElse("unknown"))
                .put("url", cfg.url());
    }

    public JsonNode listJobs(String folder) {
        String base = folder == null || folder.isBlank() ? "" : jobPath(folder);
        return getJson(base + "/api/json?tree=jobs[name,fullName,url,color,_class,"
                + "lastBuild[number,result,timestamp,building]]");
    }

    public JsonNode jobInfo(String job) {
        return getJson(jobPath(job) + "/api/json?tree=name,fullName,description,url,buildable,inQueue,color,"
                + "lastBuild[number,result,building,timestamp],lastSuccessfulBuild[number],lastFailedBuild[number],"
                + "property[parameterDefinitions[name,type,description,defaultParameterValue[value],choices]]");
    }

    public boolean jobExists(String job) {
        Resp r = get(jobPath(job) + "/api/json?tree=name");
        if (r.status() == 404) {
            return false;
        }
        if (!r.ok()) {
            throw HttpSupport.failure("Jenkins job lookup " + job, r);
        }
        return true;
    }

    public String jobConfig(String job) {
        Resp r = get(jobPath(job) + "/config.xml");
        if (!r.ok()) {
            throw HttpSupport.failure("Reading config of " + job, r);
        }
        return r.body();
    }

    public JsonNode build(String job, int number) {
        return getJson(jobPath(job) + "/" + number + "/api/json?tree=number,result,building,duration,"
                + "estimatedDuration,timestamp,url,displayName,description,inProgress");
    }

    public int lastBuildNumber(String job) {
        JsonNode n = getJson(jobPath(job) + "/api/json?tree=lastBuild[number]").path("lastBuild").path("number");
        if (n.isMissingNode()) {
            throw new IllegalStateException("Job " + job + " has no builds yet");
        }
        return n.asInt();
    }

    /** Stage view (pipeline-stage-view plugin). Returns null when the plugin is missing. */
    public List<Map<String, Object>> stages(String job, int number) {
        Resp r = get(jobPath(job) + "/" + number + "/wfapi/describe");
        if (r.status() == 404) {
            return null;
        }
        if (!r.ok()) {
            throw HttpSupport.failure("Stage view of " + job + " #" + number, r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : r.json().path("stages")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.path("name").asText());
            m.put("status", s.path("status").asText());
            m.put("durationSeconds", s.path("durationMillis").asLong() / 1000);
            out.add(m);
        }
        return out;
    }

    /** Last {@code lines} lines of the console log, at most {@code maxChars} characters. */
    public String consoleTail(String job, int number, int lines, int maxChars) {
        Resp r = get(jobPath(job) + "/" + number + "/consoleText");
        if (!r.ok()) {
            throw HttpSupport.failure("Console of " + job + " #" + number, r);
        }
        String[] all = r.body().split("\n", -1);
        int from = Math.max(0, all.length - Math.max(1, lines));
        String tail = String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
        return tail.length() > maxChars ? tail.substring(tail.length() - maxChars) : tail;
    }

    // ------------------------------------------------------------------ writes

    /** Queue a build; returns the queue item id. */
    public long trigger(String job, Map<String, String> params) {
        String path = jobPath(job);
        Resp r;
        if (params == null || params.isEmpty()) {
            r = post(path + "/build", null, null);
            if (r.status() == 400) { // parameterized job: queue with its defaults
                r = post(path + "/buildWithParameters", null, null);
            }
        } else {
            String form = params.entrySet().stream()
                    .map(e -> HttpSupport.enc(e.getKey()) + "=" + HttpSupport.enc(e.getValue()))
                    .collect(Collectors.joining("&"));
            r = post(path + "/buildWithParameters", "application/x-www-form-urlencoded", form);
        }
        if (r.status() != 201 && !r.ok()) {
            throw HttpSupport.failure("Triggering " + job, r);
        }
        String location = r.headers().firstValue("Location").orElse("");
        Matcher m = QUEUE_ID.matcher(location);
        if (!m.find()) {
            throw new IllegalStateException("Jenkins queued " + job + " but returned no queue location");
        }
        return Long.parseLong(m.group(1));
    }

    /** Wait until a queue item turns into a build. Returns the build number, or -1 on timeout. */
    public int awaitStart(long queueId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            JsonNode q = getJson("/queue/item/" + queueId + "/api/json");
            if (q.path("cancelled").asBoolean(false)) {
                throw new IllegalStateException("Queue item " + queueId + " was cancelled");
            }
            JsonNode n = q.path("executable").path("number");
            if (!n.isMissingNode() && !n.isNull()) {
                return n.asInt();
            }
            if (Instant.now().isAfter(deadline)) {
                return -1;
            }
            sleep(2000);
        }
    }

    /** Poll until the build finishes or the timeout passes; returns the last build JSON. */
    public JsonNode awaitFinish(String job, int number, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            JsonNode b = build(job, number);
            if (!b.path("building").asBoolean(false) || Instant.now().isAfter(deadline)) {
                return b;
            }
            sleep(5000);
        }
    }

    public void stop(String job, int number) {
        Resp r = post(jobPath(job) + "/" + number + "/stop", null, null);
        if (!r.ok() && r.status() != 302) {
            throw HttpSupport.failure("Stopping " + job + " #" + number, r);
        }
    }

    /** Create the job, or replace its config.xml if it exists. Returns "created" or "updated". */
    public String upsertJob(String job, String configXml) {
        if (jobExists(job)) {
            Resp r = post(jobPath(job) + "/config.xml", "application/xml", configXml);
            if (!r.ok()) {
                throw HttpSupport.failure("Updating job " + job, r);
            }
            return "updated";
        }
        int slash = job.lastIndexOf('/');
        String folder = slash > 0 ? jobPath(job.substring(0, slash)) : "";
        String leaf = slash > 0 ? job.substring(slash + 1) : job;
        Resp r = post(folder + "/createItem?name=" + HttpSupport.enc(leaf), "application/xml", configXml);
        if (!r.ok()) {
            throw HttpSupport.failure("Creating job " + job, r);
        }
        return "created";
    }

    /** Create or update a credential in the system store, global domain. */
    public String upsertCredential(String id, String credentialXml) {
        String store = "/credentials/store/system/domain/_";
        Resp r = post(store + "/credential/" + HttpSupport.enc(id) + "/config.xml", "application/xml", credentialXml);
        if (r.ok()) {
            return "updated";
        }
        if (r.status() != 404) {
            throw HttpSupport.failure("Updating credential " + id, r);
        }
        r = post(store + "/createCredentials", "application/xml", credentialXml);
        if (!r.ok()) {
            throw HttpSupport.failure("Creating credential " + id
                    + " (is the credentials plugin installed and does the user have Credentials/Create?)", r);
        }
        return "created";
    }

    // ------------------------------------------------------------------ plumbing

    private JsonNode getJson(String path) {
        Resp r = get(path);
        if (!r.ok()) {
            throw HttpSupport.failure("Jenkins " + name() + " GET " + path.replaceAll("\\?.*", ""), r);
        }
        return r.json();
    }

    private Resp get(String path) {
        return HttpSupport.send(http, request(path).GET().build());
    }

    private Resp post(String path, String contentType, String body) {
        Resp r = HttpSupport.send(http, postRequest(path, contentType, body));
        if (r.status() == 403 && r.body() != null && r.body().toLowerCase().contains("crumb")) {
            fetchCrumb();
            r = HttpSupport.send(http, postRequest(path, contentType, body));
        }
        return r;
    }

    private HttpRequest postRequest(String path, String contentType, String body) {
        HttpRequest.Builder b = request(path)
                .POST(body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType + "; charset=utf-8");
        }
        String[] c = crumb;
        if (c != null) {
            b.header(c[0], c[1]);
        }
        return b.build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(HttpSupport.stripSlash(cfg.url()) + path))
                .timeout(TIMEOUT)
                .header("Authorization", HttpSupport.basicAuth(cfg.username(), token.get()));
    }

    private void fetchCrumb() {
        JsonNode c = getJson("/crumbIssuer/api/json");
        crumb = new String[] {c.path("crumbRequestField").asText("Jenkins-Crumb"), c.path("crumb").asText()};
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
