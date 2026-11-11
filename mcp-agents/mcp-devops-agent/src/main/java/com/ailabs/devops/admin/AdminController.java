package com.ailabs.devops.admin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ailabs.devops.access.Redactor;
import com.ailabs.devops.access.SecretResolver;
import com.ailabs.devops.config.ConfigStore;
import com.ailabs.devops.config.DevopsProperties;
import com.ailabs.devops.config.DevopsProperties.DockerRegistry;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.config.DevopsProperties.KubeCluster;
import com.ailabs.devops.config.Systems;
import com.ailabs.devops.core.ConnectionTester;
import com.ailabs.devops.core.GuardedToolCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Admin UI and its JSON API, under {@code /admin}. Lets an operator edit the systems in
 * {@code systems.yml}, test connections and check secret references. Secret values are never
 * shown or accepted: only references.
 */
@RestController
@Profile("admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Logger audit = LoggerFactory.getLogger("audit");
    private static final Map<String, MediaType> ASSETS = Map.of(
            "app.js", MediaType.valueOf("text/javascript"),
            "app.css", MediaType.valueOf("text/css"));

    private final ConfigStore store;
    private final ConnectionTester tester;
    private final SecretResolver secrets;
    private final Redactor redactor;
    private final AdminSessions sessions;
    private final DevopsProperties props;
    private final ObjectMapper json;
    private final AdminPassword password = AdminPassword.atHome();

    public AdminController(ConfigStore store, ConnectionTester tester, SecretResolver secrets, Redactor redactor,
                           AdminSessions sessions, DevopsProperties props, ObjectMapper json) {
        this.store = store;
        this.tester = tester;
        this.secrets = secrets;
        this.redactor = redactor;
        this.sessions = sessions;
        this.props = props;
        this.json = json;
    }

    /** First start: create a random admin password and print it once. */
    @PostConstruct
    void ensurePassword() {
        if (!"password".equals(props.admin().auth())) {
            return;
        }
        if (props.admin().minPasswordLength() < AdminPassword.MIN_LENGTH) {
            log.warn("devops.admin.min-password-length is {}; admin passwords shorter than {} are allowed",
                    props.admin().minPasswordLength(), AdminPassword.MIN_LENGTH);
        }
        if (!password.isSet()) {
            char[] pw = AdminPassword.generate();
            password.set(pw);
            System.out.println("""

                    ==================================================================
                     mcp-devops-agent admin UI password (shown once): %s
                     Saved as a hash in %s
                     Change it with: java -jar mcp-devops-agent.jar admin-password
                    ==================================================================
                    """.formatted(new String(pw), password.file()));
            log.info("Generated admin password, hash stored in {}", password.file());
        }
    }

    // ------------------------------------------------------------------ static UI

    @GetMapping({"/admin", "/admin/"})
    public ResponseEntity<String> index() throws IOException {
        return asset("index.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/admin/app.js")
    public ResponseEntity<String> script() throws IOException {
        return asset("app.js", ASSETS.get("app.js"));
    }

    @GetMapping("/admin/app.css")
    public ResponseEntity<String> style() throws IOException {
        return asset("app.css", ASSETS.get("app.css"));
    }

    private static ResponseEntity<String> asset(String name, MediaType type) throws IOException {
        try (InputStream in = new ClassPathResource("admin/" + name).getInputStream()) {
            return ResponseEntity.ok().contentType(type).body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ session

    @GetMapping("/admin/api/session")
    public Map<String, Object> session(HttpServletRequest req) {
        boolean ok = props.admin().passwordless() || sessions.valid(AdminSecurityFilter.sessionOf(req));
        return Map.of("authenticated", ok, "mode", props.admin().auth());
    }

    public record Login(String password) {}

    @PostMapping("/admin/api/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Login body, HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        if (props.admin().passwordless()) {
            return ResponseEntity.ok(Map.of("authenticated", true));
        }
        if (sessions.locked(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "too many failed logins; wait 5 minutes"));
        }
        if (body == null || body.password() == null || !password.verify(body.password().toCharArray())) {
            sessions.failed(ip);
            audit.warn("ADMIN login failed ip={}", ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "wrong password"));
        }
        sessions.succeeded(ip);
        audit.info("ADMIN login ip={}", ip);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(sessions.create(), req.isSecure(), sessions.ttl().toSeconds()))
                .body(Map.of("authenticated", true));
    }

    @PostMapping("/admin/api/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req) {
        sessions.end(AdminSecurityFilter.sessionOf(req));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", req.isSecure(), 0)).body(Map.of("authenticated", false));
    }

    public record PasswordChange(String current, String next) {}

    @PostMapping("/admin/api/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody PasswordChange body, HttpServletRequest req) {
        if (!"password".equals(props.admin().auth())) {
            return ResponseEntity.badRequest().body(Map.of("error", "password sign-in is off (devops.admin.auth=" + props.admin().auth() + ")"));
        }
        if (body == null || body.current() == null || !password.verify(body.current().toCharArray())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "current password is wrong"));
        }
        password.set(body.next() == null ? new char[0] : body.next().toCharArray(), props.admin().minPasswordLength());
        sessions.endAll();
        audit.info("ADMIN password changed ip={}", req.getRemoteAddr());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(sessions.create(), req.isSecure(), sessions.ttl().toSeconds()))
                .body(Map.of("changed", true));
    }

    private static String cookie(String value, boolean secure, long maxAge) {
        return ResponseCookie.from(AdminSessions.COOKIE, value).path("/admin").httpOnly(true).sameSite("Strict")
                .secure(secure).maxAge(maxAge).build().toString();
    }

    // ------------------------------------------------------------------ config

    @GetMapping("/admin/api/config")
    public Map<String, Object> config() {
        ConfigStore.Snapshot s = store.snapshot();
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("defaultProvider", secrets.defaultScheme());
        access.put("providers", secrets.schemes());
        access.put("cacheTtl", props.access().cacheTtl().toString());
        var server = props.access().server();
        access.put("serverUrl", server != null ? server.url() : null);
        access.put("pathTemplate", server != null ? server.pathTemplate() : null);
        access.put("vaultAddress", props.access().vault() != null ? props.access().vault().address() : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", s.version());
        out.put("file", s.file().toString());
        out.put("systems", s.systems());
        out.put("yaml", store.toYaml(s.systems()));
        out.put("access", access);
        out.put("admin", Map.of("minPasswordLength", props.admin().minPasswordLength(), "auth", props.admin().auth()));
        return out;
    }

    public record Save(String version, Systems systems) {}

    @PutMapping("/admin/api/config")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Save body, HttpServletRequest req) {
        if (body == null || body.systems() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "systems is required"));
        }
        try {
            var s = store.save(body.systems(), body.version(), "ip=" + req.getRemoteAddr());
            return ResponseEntity.ok(Map.of("version", s.version(), "yaml", store.toYaml(s.systems())));
        } catch (ConfigStore.ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (ConfigStore.InvalidConfigException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "validation failed", "errors", e.errors()));
        }
    }

    /** Run a dry-run validation without saving. */
    @PostMapping("/admin/api/validate")
    public Map<String, Object> validate(@RequestBody Systems systems) {
        List<String> errors = systems.validate();
        return Map.of("valid", errors.isEmpty(), "errors", errors);
    }

    public record Test(String kind, JsonNode system) {}

    @PostMapping("/admin/api/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody Test body) {
        if (body == null || body.kind() == null || body.system() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind and system are required"));
        }
        Map<String, Object> result = switch (body.kind()) {
            case "jenkins" -> tester.jenkins(json.convertValue(body.system(), JenkinsServer.class));
            case "dockerhub" -> tester.dockerhub(json.convertValue(body.system(), DockerRegistry.class));
            case "kubernetes" -> tester.kubernetes(json.convertValue(body.system(), KubeCluster.class));
            default -> Map.of("ok", false, "detail", "unknown kind " + body.kind());
        };
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.computeIfPresent("detail", (k, v) -> redactor.redact(String.valueOf(v)));
        return ResponseEntity.ok(out);
    }

    public record RefCheck(String ref) {}

    @PostMapping("/admin/api/check-ref")
    public Map<String, Object> checkRef(@RequestBody RefCheck body) {
        try {
            String scheme = secrets.probe(body == null ? null : body.ref(), "admin:check-ref");
            return Map.of("ok", true, "detail", "resolved via " + scheme);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "detail", redactor.redact(GuardedToolCallback.firstMessage(e)));
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", redactor.redact(GuardedToolCallback.firstMessage(e))));
    }
}
