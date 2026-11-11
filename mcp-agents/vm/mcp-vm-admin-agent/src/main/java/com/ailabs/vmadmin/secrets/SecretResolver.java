package com.ailabs.vmadmin.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ailabs.vmadmin.config.AdminProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a token reference into the token.
 *
 * <pre>
 *   store:web-01           -> tokens.yml, written by the UI when you paste a token
 *   env:WEB01_TOKEN        -> environment variable
 *   path:/run/keys/web01   -> contents of a file
 *   vault:vms/web-01#token -> HashiCorp Vault KV v2 (vmadmin.vault)
 * </pre>
 *
 * Values are never logged or returned by the API; the UI only ever sees the reference.
 */
@Component
public class SecretResolver {

    public static final List<String> SCHEMES = List.of("store", "env", "path", "vault");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TokenStore store;
    private final AdminProperties.Vault vault;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SecretResolver(TokenStore store, AdminProperties props) {
        this.store = store;
        this.vault = props.vault();
    }

    public String resolve(String ref, String purpose) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("No token configured for " + purpose);
        }
        int i = ref.indexOf(':');
        String scheme = i > 0 ? ref.substring(0, i) : "";
        String key = i > 0 ? ref.substring(i + 1) : ref;
        return switch (scheme) {
            case "store" -> store.get(key).orElseThrow(() -> new IllegalStateException(
                    "No stored token '" + key + "' (" + purpose + "); paste it again in the host settings"));
            case "env" -> env(key);
            case "path" -> path(key);
            case "vault" -> vault(key, purpose);
            default -> throw new IllegalStateException("Unknown token reference '" + ref + "' for " + purpose
                    + "; use one of " + SCHEMES.stream().map(s -> s + ":").toList());
        };
    }

    /** For the credentials of the secret stores themselves and the MCP token: env: or path: only. */
    public static String bootstrap(String ref, String what) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith("env:")) {
            return env(ref.substring(4));
        }
        if (ref.startsWith("path:")) {
            return path(ref.substring(5));
        }
        throw new IllegalStateException(what + " must be an env: or path: reference");
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is not set");
        }
        return v.trim();
    }

    static String path(String file) {
        Path p = Path.of(file.startsWith("~/") ? System.getProperty("user.home") + file.substring(1) : file);
        try {
            String v = Files.readString(p).trim();
            if (v.isEmpty()) {
                throw new IllegalStateException(p + " is empty");
            }
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + p + ": " + e.getMessage(), e);
        }
    }

    private String vault(String key, String purpose) {
        if (vault == null || vault.address() == null || vault.address().isBlank()) {
            throw new IllegalStateException("vault: reference used but vmadmin.vault.address is not set");
        }
        String token = bootstrap(vault.tokenRef(), "vmadmin.vault.token-ref");
        if (token == null) {
            throw new IllegalStateException("vmadmin.vault.token-ref is not set");
        }
        int h = key.lastIndexOf('#');
        String path = h >= 0 ? key.substring(0, h) : key;
        String field = h >= 0 ? key.substring(h + 1) : "token";
        String enc = String.join("/", java.util.Arrays.stream(path.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")).toList());
        String base = vault.address().endsWith("/") ? vault.address().substring(0, vault.address().length() - 1) : vault.address();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/v1/" + vault.kvMount() + "/data/" + enc))
                .timeout(Duration.ofSeconds(10)).header("X-Vault-Token", token).GET();
        if (vault.namespace() != null && !vault.namespace().isBlank()) {
            req.header("X-Vault-Namespace", vault.namespace());
        }
        try {
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                throw new IllegalStateException("Vault has no secret at " + path);
            }
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("Vault read of " + path + " failed: HTTP " + r.statusCode());
            }
            JsonNode v = JSON.readTree(r.body()).path("data").path("data").path(field);
            if (v.isMissingNode() || v.isNull()) {
                throw new IllegalStateException("Vault secret " + path + " has no field '" + field + "'");
            }
            return v.asText();
        } catch (IOException e) {
            throw new IllegalStateException("Vault unreachable (" + purpose + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
