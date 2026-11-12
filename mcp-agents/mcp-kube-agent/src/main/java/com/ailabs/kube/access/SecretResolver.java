package com.ailabs.kube.access;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ailabs.kube.config.KubeProperties;

/**
 * Turns a secret reference into a value.
 *
 * <pre>
 *   path:~/.kube-agent/lab.token   -> contents of a file (service account tokens, CA certificates)
 *   env:K8S_LAB_TOKEN              -> environment variable
 *   file:lab/token                 -> key in ~/.kube-agent/secrets.yml
 * </pre>
 *
 * Values are cached for {@code kube.access.cache-ttl} and registered with the {@link Redactor}.
 * Only the reference is ever logged.
 */
@Component
public class SecretResolver {

    private static final Logger log = LoggerFactory.getLogger(SecretResolver.class);

    private record Cached(String value, Instant expires) {}

    private final Map<String, SecretProvider> providers;
    private final Duration ttl;
    private final Redactor redactor;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SecretResolver(List<SecretProvider> providers, KubeProperties props, Redactor redactor) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(SecretProvider::scheme, Function.identity(), (a, b) -> a, TreeMap::new));
        this.ttl = props.access().cacheTtl();
        this.redactor = redactor;
    }

    public String resolve(String ref, String purpose) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException("No secret reference configured for " + purpose);
        }
        Cached c = cache.get(ref);
        if (c != null && Instant.now().isBefore(c.expires())) {
            return c.value();
        }
        int i = ref.indexOf(':');
        SecretProvider provider = i > 0 ? providers.get(ref.substring(0, i)) : null;
        if (provider == null) {
            throw new IllegalStateException("Secret reference '" + ref + "' must start with one of "
                    + providers.keySet().stream().map(s -> s + ":").toList());
        }
        log.info("resolving secret ref={} purpose={}", ref, purpose);
        String value = provider.fetch(ref.substring(i + 1), purpose);
        redactor.register(value);
        if (!ttl.isZero() && !ttl.isNegative()) {
            cache.put(ref, new Cached(value, Instant.now().plus(ttl)));
        }
        return value;
    }

    public String resolveOptional(String ref, String purpose) {
        return ref == null || ref.isBlank() ? null : resolve(ref, purpose);
    }

    public void clearCache() {
        cache.clear();
    }

    public Set<String> schemes() {
        return providers.keySet();
    }
}
