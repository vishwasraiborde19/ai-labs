package com.ailabs.devops.access;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.ailabs.devops.config.DevopsProperties;
import com.ailabs.devops.http.HttpSupport;
import com.ailabs.devops.http.HttpSupport.Resp;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code vault:<path>#<field>} reads a HashiCorp Vault KV v2 secret
 * ({@code GET /v1/{mount}/data/{path}}). The field defaults to {@code value}.
 */
@Component
public class VaultSecretProvider implements SecretProvider {

    private final DevopsProperties.Vault cfg;
    private final HttpClient http = HttpSupport.client(false, null, false, Duration.ofSeconds(10));

    public VaultSecretProvider(DevopsProperties props) {
        this.cfg = props.access().vault();
    }

    @Override
    public String scheme() {
        return "vault";
    }

    @Override
    public String fetch(String key, String purpose) {
        if (cfg == null || cfg.address() == null || cfg.address().isBlank()) {
            throw new IllegalStateException("Secret 'vault:" + key + "' requested but devops.access.vault.address is not set");
        }
        int hash = key.lastIndexOf('#');
        String path = hash >= 0 ? key.substring(0, hash) : key;
        String field = hash >= 0 ? key.substring(hash + 1) : "value";

        String token = BootstrapSecrets.resolve(cfg.tokenRef(), "devops.access.vault.token-ref");
        if (token == null) {
            throw new IllegalStateException("devops.access.vault.token-ref is not set");
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(HttpSupport.stripSlash(cfg.address())
                        + "/v1/" + cfg.kvMount() + "/data/" + AccessServerSecretProvider.encodeKey(path)))
                .timeout(Duration.ofSeconds(10))
                .header("X-Vault-Token", token)
                .GET();
        if (cfg.namespace() != null && !cfg.namespace().isBlank()) {
            req.header("X-Vault-Namespace", cfg.namespace());
        }
        Resp r = HttpSupport.send(http, req.build());
        if (r.status() == 404) {
            throw new IllegalStateException("Vault has no secret at " + path);
        }
        if (!r.ok()) {
            throw HttpSupport.failure("Vault read of " + path, r);
        }
        JsonNode v = r.json().path("data").path("data").path(field);
        if (v.isMissingNode() || v.isNull()) {
            throw new IllegalStateException("Vault secret " + path + " has no field '" + field + "'");
        }
        return v.asText();
    }
}
