package com.ailabs.devops.access;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ailabs.devops.config.DevopsProperties;

/**
 * Turns a secret reference into a value.
 *
 * <pre>
 *   access:jenkins/main/api-token   -> access server
 *   vault:apps/jenkins#token        -> HashiCorp Vault KV v2
 *   file:dockerhub/password         -> local secrets.yml
 *   env:K8S_DEV_TOKEN               -> environment variable
 *   path:/etc/ssl/k8s-ca.pem        -> contents of a file
 *   jenkins/main/api-token          -> devops.access.default-provider
 * </pre>
 *
 * Values are cached for {@code devops.access.cache-ttl} (0 disables caching) and registered with
 * the {@link Redactor}. Only the reference, never the value, is logged.
 */
@Component
public class SecretResolver {

    private static final Logger log = LoggerFactory.getLogger(SecretResolver.class);

    private record Cached(String value, Instant expires) {}

    private final Map<String, SecretProvider> providers;
    private final String defaultScheme;
    private final Duration ttl;
    private final Redactor redactor;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SecretResolver(List<SecretProvider> providers, DevopsProperties props, Redactor redactor) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(SecretProvider::scheme, Function.identity(), (a, b) -> a, TreeMap::new));
        this.defaultScheme = props.access().defaultProvider();
        this.ttl = props.access().cacheTtl();
        this.redactor = redactor;
        if (!this.providers.containsKey(defaultScheme)) {
            log.warn("devops.access.default-provider '{}' is not available; providers: {}",
                    defaultScheme, this.providers.keySet());
        }
    }

    /** Resolve a required reference. */
    public String resolve(String ref, String purpose) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("No secret reference configured for " + purpose);
        }
        Cached c = cache.get(ref);
        if (c != null && Instant.now().isBefore(c.expires())) {
            return c.value();
        }
        Parsed p = parse(ref);
        SecretProvider provider = providers.get(p.scheme());
        if (provider == null) {
            throw new IllegalStateException("No secret provider for scheme '" + p.scheme() + "' (ref " + ref
                    + "). Available: " + providers.keySet());
        }
        log.info("resolving secret ref={} purpose={}", ref, purpose);
        String value = provider.fetch(p.key(), purpose);
        redactor.register(value);
        if (!ttl.isZero() && !ttl.isNegative()) {
            cache.put(ref, new Cached(value, Instant.now().plus(ttl)));
        }
        return value;
    }

    /** Resolve an optional reference; null or blank gives null. */
    public String resolveOptional(String ref, String purpose) {
        return ref == null || ref.isBlank() ? null : resolve(ref, purpose);
    }

    /**
     * Check that a reference resolves, without caching it. Returns the provider that answered;
     * the value itself never leaves this class (it is registered with the redactor).
     */
    public String probe(String ref, String purpose) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("reference is empty");
        }
        Parsed p = parse(ref);
        SecretProvider provider = providers.get(p.scheme());
        if (provider == null) {
            throw new IllegalStateException("No secret provider for scheme '" + p.scheme() + "'. Available: " + providers.keySet());
        }
        log.info("probing secret ref={} purpose={}", ref, purpose);
        redactor.register(provider.fetch(p.key(), purpose));
        return p.scheme();
    }

    public String defaultScheme() {
        return defaultScheme;
    }

    public void clearCache() {
        cache.clear();
    }

    public java.util.Set<String> schemes() {
        return providers.keySet();
    }

    record Parsed(String scheme, String key) {}

    Parsed parse(String ref) {
        int i = ref.indexOf(':');
        if (i > 0 && providers.containsKey(ref.substring(0, i))) {
            return new Parsed(ref.substring(0, i), ref.substring(i + 1));
        }
        return new Parsed(defaultScheme, ref);
    }
}
