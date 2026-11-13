package com.ailabs.vmadmin.pve;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ailabs.vmadmin.agent.AgentClient;
import com.ailabs.vmadmin.secrets.SecretResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Proxmox VE REST API ({@code /api2/json}) with an API token. Any cluster node can answer for the
 * whole cluster, so requests go to the last node that worked and fail over to the others when it is
 * unreachable or returns a 5xx. 401 and 403 are not retried: every node would say the same.
 */
@Component
public class ProxmoxClient {

    private static final Logger log = LoggerFactory.getLogger(ProxmoxClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A non-2xx answer from Proxmox. Extends IllegalStateException so the UI shows it as a 502 with the message. */
    public static class PveException extends IllegalStateException {
        private final int status;

        PveException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final ProxmoxProperties props;
    private final SecretResolver secrets;
    private volatile HttpClient http;
    private volatile int preferred;
    private volatile String lastEndpoint;

    public ProxmoxClient(ProxmoxProperties props, SecretResolver secrets) {
        this.props = props;
        this.secrets = secrets;
    }

    public boolean configured() {
        return props.configured();
    }

    public List<String> endpoints() {
        return props.endpoints();
    }

    /** The endpoint that answered the most recent request. */
    public String lastEndpoint() {
        return lastEndpoint;
    }

    /** GET {@code /api2/json<path>} and return its {@code data}. */
    public JsonNode get(String path) {
        if (!configured()) {
            throw new IllegalStateException("Proxmox is not configured: set vmadmin.proxmox.endpoints and token-ref in config.yml");
        }
        String token = secrets.resolve(props.tokenRef(), "vmadmin.proxmox.token-ref");
        String auth = token.startsWith("PVEAPIToken=") ? token : "PVEAPIToken=" + token;
        List<String> eps = props.endpoints();
        List<String> errors = new ArrayList<>();
        int start = preferred;
        for (int i = 0; i < eps.size(); i++) {
            int idx = (start + i) % eps.size();
            String base = eps.get(idx);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api2/json" + path))
                    .timeout(props.timeout()).header("Authorization", auth).header("Accept", "application/json").GET().build();
            try {
                HttpResponse<String> r = client().send(req, HttpResponse.BodyHandlers.ofString());
                int sc = r.statusCode();
                if (sc / 100 == 2) {
                    preferred = idx;
                    lastEndpoint = base;
                    return JSON.readTree(r.body()).path("data");
                }
                String reason = reason(r);
                if (sc == 401) {
                    throw new PveException(401, "Proxmox rejected the API token (401) at " + base
                            + ". Check vmadmin.proxmox.token-ref holds user@realm!tokenid=secret.");
                }
                if (sc == 403) {
                    throw new PveException(403, "Proxmox denied " + path + " (403): the token needs more privileges. " + reason);
                }
                if (sc / 100 == 4) {
                    throw new PveException(sc, "Proxmox " + path + ": HTTP " + sc + " " + reason);
                }
                errors.add(base + ": HTTP " + sc + " " + reason);
            } catch (IOException e) {
                errors.add(base + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            }
        }
        log.warn("Proxmox unreachable on every endpoint: {}", errors);
        throw new PveException(502, "No Proxmox node answered: " + String.join("; ", errors));
    }

    /** Proxmox puts the reason in the HTTP status line, which Java drops; the body sometimes has errors. */
    private static String reason(HttpResponse<String> r) {
        String b = r.body() == null ? "" : r.body().trim();
        return b.length() > 300 ? b.substring(0, 300) : b;
    }

    private HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(props.timeout());
                    if (props.caCertRef() != null) {
                        b.sslContext(AgentClient.trusting(props.caCertRef().substring("path:".length())));
                    } else if (props.insecureSkipTlsVerify()) {
                        b.sslContext(trustAnything());
                    }
                    http = b.build();
                }
                c = http;
            }
        }
        return c;
    }

    /**
     * Accepts any certificate and any host name. An X509ExtendedTrustManager is needed: with a plain
     * X509TrustManager the JDK still checks the host name, which fails for IP endpoints on many setups.
     */
    static SSLContext trustAnything() {
        TrustManager tm = new X509ExtendedTrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
