package com.ailabs.devops.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ailabs.devops.config.DevopsProperties;

class SecretResolverTest {

    static DevopsProperties props(String defaultProvider, Duration ttl) {
        return new DevopsProperties(new DevopsProperties.Access(defaultProvider, ttl, null, null, null),
                null, null, null, null, null, null, null);
    }

    static SecretProvider fake(String scheme, AtomicInteger calls) {
        return new SecretProvider() {
            public String scheme() { return scheme; }
            public String fetch(String key, String purpose) { calls.incrementAndGet(); return scheme + "-secret-for-" + key; }
        };
    }

    @Test
    void routesBySchemeAndFallsBackToDefault() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger v = new AtomicInteger();
        SecretResolver r = new SecretResolver(List.of(fake("access", a), fake("vault", v)), props("access", Duration.ZERO), new Redactor());

        assertThat(r.resolve("vault:apps/x#token", "t")).isEqualTo("vault-secret-for-apps/x#token");
        assertThat(r.resolve("jenkins/main/api-token", "t")).isEqualTo("access-secret-for-jenkins/main/api-token");
        // an unknown prefix is part of the key, not a scheme
        assertThat(r.resolve("team:thing", "t")).isEqualTo("access-secret-for-team:thing");
    }

    @Test
    void cachesWithinTtl() {
        AtomicInteger a = new AtomicInteger();
        SecretResolver r = new SecretResolver(List.of(fake("access", a)), props("access", Duration.ofMinutes(5)), new Redactor());
        r.resolve("k", "t");
        r.resolve("k", "t");
        assertThat(a.get()).isEqualTo(1);
        r.clearCache();
        r.resolve("k", "t");
        assertThat(a.get()).isEqualTo(2);
    }

    @Test
    void resolvedValuesAreRedacted() {
        Redactor red = new Redactor();
        SecretResolver r = new SecretResolver(List.of(fake("access", new AtomicInteger())), props("access", Duration.ZERO), red);
        String s = r.resolve("db", "t");
        assertThat(red.redact("login with " + s + " ok")).isEqualTo("login with **** ok");
    }

    @Test
    void blankRefAndUnknownDefaultFailClearly() {
        SecretResolver r = new SecretResolver(List.of(fake("env", new AtomicInteger())), props("access", Duration.ZERO), new Redactor());
        assertThatThrownBy(() -> r.resolve(" ", "jenkins:main")).hasMessageContaining("jenkins:main");
        assertThatThrownBy(() -> r.resolve("some/key", "x")).hasMessageContaining("No secret provider for scheme 'access'");
    }
}
