package com.ailabs.devops.access;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import org.springframework.stereotype.Component;

import com.ailabs.devops.config.DevopsProperties;
import com.ailabs.devops.http.HttpSupport;
import com.ailabs.devops.http.HttpSupport.Resp;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code access:<key>} fetches a secret from the access server.
 *
 * <p>Contract (all of it configurable under {@code devops.access.server}):
 * <pre>
 *   GET {url}{pathTemplate}            pathTemplate default /api/v1/secrets/{key}
 *   Authorization: Bearer {tokenRef}
 *   X-Agent-Name: mcp-devops-agent
 *   X-Access-Purpose: jenkins:main      (what the secret is for, for the server's audit log)
 *
 *   200 application/json  {"value": "..."}   (field set by valueField, dot paths allowed: data.password)
 *   200 text/plain        ...                (whole body is the value)
 *   404                   unknown key
 *   401/403               agent not allowed to read this key
 * </pre>
 */
@Component
public class AccessServerSecretProvider implements SecretProvider {

    private final DevopsProperties.AccessServer cfg;
    private volatile HttpClient http;

    public AccessServerSecretProvider(DevopsProperties props) {
        this.cfg = props.access().server();
    }

    @Override
    public String scheme() {
        return "access";
    }

    @Override
    public String fetch(String key, String purpose) {
        if (cfg == null || cfg.url() == null || cfg.url().isBlank()) {
            throw new IllegalStateException("Secret 'access:" + key + "' requested but devops.access.server.url is not set");
        }
        String path = cfg.pathTemplate().replace("{key}", encodeKey(key));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(HttpSupport.stripSlash(cfg.url()) + path))
                .timeout(cfg.timeout())
                .header("Accept", "application/json, text/plain")
                .header("X-Agent-Name", "mcp-devops-agent")
                .header("X-Access-Purpose", purpose == null ? "-" : purpose)
                .GET();
        String token = BootstrapSecrets.resolve(cfg.tokenRef(), "devops.access.server.token-ref");
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        cfg.headers().forEach(req::header);

        Resp r = HttpSupport.send(client(), req.build());
        return switch (r.status()) {
            case 200 -> extract(r, key);
            case 404 -> throw new IllegalStateException("Access server has no secret '" + key + "'");
            case 401, 403 -> throw new IllegalStateException("Access server denied secret '" + key + "' (HTTP "
                    + r.status() + "). Check the agent's token and the server's policy for this key.");
            default -> throw HttpSupport.failure("Access server lookup of '" + key + "'", r);
        };
    }

    private String extract(Resp r, String key) {
        String type = r.headers().firstValue("Content-Type").orElse("");
        if (!type.contains("json")) {
            return r.body().strip();
        }
        JsonNode node = r.json();
        for (String part : cfg.valueField().split("\\.")) {
            node = node.path(part);
        }
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("Access server response for '" + key + "' has no field '" + cfg.valueField() + "'");
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    /** Keys are hierarchical (jenkins/main/api-token); keep the slashes, encode each segment. */
    static String encodeKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (String seg : key.split("/", -1)) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            if (seg.equals("..") || seg.equals(".")) {
                throw new IllegalArgumentException("Invalid secret key: " + key);
            }
            sb.append(HttpSupport.enc(seg));
        }
        return sb.toString();
    }

    private HttpClient client() {
        if (http == null) {
            String ca = BootstrapSecrets.resolve(cfg.caCertRef(), "devops.access.server.ca-cert-ref");
            http = HttpSupport.client(cfg.insecureSkipTlsVerify(), ca, false, cfg.timeout());
        }
        return http;
    }
}
