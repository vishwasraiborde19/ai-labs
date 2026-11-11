package com.ailabs.devops.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ailabs.devops.FakeServer;
import com.ailabs.devops.FakeServer.Res;
import com.ailabs.devops.config.DevopsProperties;

class AccessServerSecretProviderTest {

    static AccessServerSecretProvider provider(String url, String valueField) {
        var server = new DevopsProperties.AccessServer(url, null, "path:" + tokenFile(), valueField, Map.of("X-Team", "platform"),
                Duration.ofSeconds(5), false, null);
        return new AccessServerSecretProvider(new DevopsProperties(
                new DevopsProperties.Access("access", null, server, null, null), null, null, null, null, null, null, null));
    }

    static String tokenFile() {
        try {
            var f = java.nio.file.Files.createTempFile("access-token", ".txt");
            java.nio.file.Files.writeString(f, "agent-token-123\n");
            f.toFile().deleteOnExit();
            return f.toString();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fetchesJsonValueWithAuthAndPurpose() throws Exception {
        try (FakeServer s = new FakeServer(Map.of(
                "GET /api/v1/secrets/jenkins/main/api-token", r -> Res.json("{\"data\":{\"value\":\"s3cret\"}}")))) {
            String v = provider(s.url(), "data.value").fetch("jenkins/main/api-token", "jenkins:main");
            assertThat(v).isEqualTo("s3cret");
            var req = s.requests.get(0);
            assertThat(req.header("Authorization")).isEqualTo("Bearer agent-token-123");
            assertThat(req.header("X-Access-Purpose")).isEqualTo("jenkins:main");
            assertThat(req.header("X-Team")).isEqualTo("platform");
        }
    }

    @Test
    void plainTextBodyIsTheValue() throws Exception {
        try (FakeServer s = new FakeServer(Map.of(
                "GET /api/v1/secrets/k", r -> new Res(200, "text/plain", "plain-value\n", Map.of())))) {
            assertThat(provider(s.url(), null).fetch("k", "p")).isEqualTo("plain-value");
        }
    }

    @Test
    void mapsErrors() throws Exception {
        try (FakeServer s = new FakeServer(Map.of(
                "GET /api/v1/secrets/denied", r -> new Res(403, "text/plain", "no", Map.of())))) {
            var p = provider(s.url(), null);
            assertThatThrownBy(() -> p.fetch("missing", "p")).hasMessageContaining("has no secret 'missing'");
            assertThatThrownBy(() -> p.fetch("denied", "p")).hasMessageContaining("denied secret 'denied'");
            assertThatThrownBy(() -> p.fetch("../admin", "p")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void serverTokenMustNotComeFromTheAccessServer() {
        assertThatThrownBy(() -> BootstrapSecrets.resolve("access:agent-token", "token"))
                .hasMessageContaining("env: or path:");
    }
}
