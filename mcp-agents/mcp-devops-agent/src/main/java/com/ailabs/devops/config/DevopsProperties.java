package com.ailabs.devops.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the agent can reach, bound from the {@code devops:} section of the config file.
 *
 * <p>No field here holds a secret. Fields ending in {@code Ref} hold a <em>secret reference</em>
 * such as {@code access:jenkins/main/api-token} or {@code env:JENKINS_TOKEN}, which
 * {@link com.ailabs.devops.access.SecretResolver} turns into a value at the moment it is needed.
 */
@ConfigurationProperties(prefix = "devops")
public record DevopsProperties(
        Access access,
        List<JenkinsServer> jenkins,
        List<DockerRegistry> dockerhub,
        List<KubeCluster> kubernetes,
        Health health,
        Http http,
        String systemsFile,
        Admin admin) {

    /**
     * {@code jenkins}, {@code dockerhub}, {@code kubernetes} and {@code health} only seed
     * {@code systems.yml} the first time it is created; after that the file (edited in the admin UI)
     * is the source of truth. See {@link ConfigStore}.
     */
    public DevopsProperties {
        access = access != null ? access : new Access(null, null, null, null, null);
        admin = admin != null ? admin : new Admin(null, null, null, null);
        jenkins = jenkins != null ? List.copyOf(jenkins) : List.of();
        dockerhub = dockerhub != null ? List.copyOf(dockerhub) : List.of();
        kubernetes = kubernetes != null ? List.copyOf(kubernetes) : List.of();
        health = health != null ? health : new Health(null);
        http = http != null ? http : new Http(null);
    }

    /** Where secrets come from. {@code defaultProvider} applies to refs written without a scheme. */
    public record Access(String defaultProvider, Duration cacheTtl, AccessServer server, SecretFile file, Vault vault) {
        public Access {
            defaultProvider = blank(defaultProvider) ? "access" : defaultProvider;
            cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofMinutes(5);
        }
    }

    /**
     * The access server: {@code GET {url}{pathTemplate}} with {@code {key}} replaced, authenticated
     * with a bearer token. The response is either plain text or JSON with the value at {@code valueField}.
     */
    public record AccessServer(String url, String pathTemplate, String tokenRef, String valueField,
                               Map<String, String> headers, Duration timeout, boolean insecureSkipTlsVerify,
                               String caCertRef) {
        public AccessServer {
            pathTemplate = blank(pathTemplate) ? "/api/v1/secrets/{key}" : pathTemplate;
            valueField = blank(valueField) ? "value" : valueField;
            headers = headers != null ? Map.copyOf(headers) : Map.of();
            timeout = timeout != null ? timeout : Duration.ofSeconds(10);
        }
    }

    public record SecretFile(String path) {}

    /** HashiCorp Vault KV v2. Refs look like {@code vault:apps/jenkins#token}. */
    public record Vault(String address, String tokenRef, String kvMount, String namespace) {
        public Vault {
            kvMount = blank(kvMount) ? "secret" : kvMount;
        }
    }

    /**
     * @param credentialSyncAllow secret refs that {@code jenkins_sync_credential} may copy into this
     *                            Jenkins; a trailing {@code *} matches a prefix. Empty disables syncing.
     */
    public record JenkinsServer(String name, String url, String username, String tokenRef,
                                boolean readOnly, boolean insecureSkipTlsVerify, String caCertRef,
                                List<String> credentialSyncAllow) {
        public JenkinsServer {
            credentialSyncAllow = credentialSyncAllow != null ? List.copyOf(credentialSyncAllow) : List.of();
        }

        public boolean maySync(String ref) {
            return ref != null && credentialSyncAllow.stream().anyMatch(p -> p.endsWith("*")
                    ? ref.startsWith(p.substring(0, p.length() - 1)) : p.equals(ref));
        }
    }

    public record DockerRegistry(String name, String namespace, String username, String passwordRef) {}

    public record KubeCluster(String name, String apiServer, String tokenRef, String caCertRef,
                              boolean insecureSkipTlsVerify, List<String> allowedNamespaces, boolean readOnly) {
        public KubeCluster {
            allowedNamespaces = allowedNamespaces != null ? List.copyOf(allowedNamespaces) : List.of();
        }

        public boolean namespaceAllowed(String ns) {
            return allowedNamespaces.isEmpty() || allowedNamespaces.contains(ns);
        }
    }

    /** {@code allowedHosts} limits which hosts health checks may call; empty means any host. */
    public record Health(List<String> allowedHosts) {
        public Health {
            allowedHosts = allowedHosts != null ? List.copyOf(allowedHosts) : List.of();
        }
    }

    /** Bearer token that HTTP-mode MCP clients must present. Unused in stdio mode. */
    public record Http(String authTokenRef) {}

    /**
     * Admin UI settings.
     *
     * @param auth how people sign in: {@code none} (default; no sign-in, loopback clients only),
     *             {@code password} (local admin password) or {@code saml} (reserved, not built yet)
     * @param allowedNetworks who may reach the UI in {@code password} mode (CIDRs)
     * @param sessionTtl how long a sign-in lasts
     * @param minPasswordLength shortest admin password accepted (default 12)
     */
    public record Admin(List<String> allowedNetworks, Duration sessionTtl, Integer minPasswordLength, String auth) {
        public static final List<String> AUTH_MODES = List.of("none", "password", "saml");

        public Admin {
            auth = auth == null || auth.isBlank() ? "none" : auth.trim().toLowerCase();
            if (!AUTH_MODES.contains(auth)) {
                throw new IllegalArgumentException("devops.admin.auth must be one of " + AUTH_MODES + ", got '" + auth + "'");
            }
            minPasswordLength = minPasswordLength != null ? minPasswordLength : 12;
            if (minPasswordLength < 1) {
                throw new IllegalArgumentException("devops.admin.min-password-length must be at least 1");
            }
            allowedNetworks = allowedNetworks != null && !allowedNetworks.isEmpty()
                    ? List.copyOf(allowedNetworks) : List.of("127.0.0.0/8", "::1/128");
            sessionTtl = sessionTtl != null ? sessionTtl : Duration.ofHours(8);
        }

        /** No sign-in: the admin UI accepts loopback clients only. */
        public boolean passwordless() {
            return "none".equals(auth);
        }
    }

    /** {@code DEVOPS_AGENT_HOME}, default {@code ~/.devops-agent}. */
    public static java.nio.file.Path home() {
        String h = System.getenv("DEVOPS_AGENT_HOME");
        return h != null && !h.isBlank() ? java.nio.file.Path.of(h)
                : java.nio.file.Path.of(System.getProperty("user.home"), ".devops-agent");
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
