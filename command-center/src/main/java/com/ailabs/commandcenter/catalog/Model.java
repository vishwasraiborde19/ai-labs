package com.ailabs.commandcenter.catalog;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The catalog: systems (apps, platforms, agents) and flows (how they work together). */
public final class Model {

    private Model() {}

    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    public static final List<String> LINK_TYPES = List.of("ui", "api", "docs", "health", "mcp", "metrics", "repo", "other");
    public static final List<String> ACCESS_METHODS = List.of("sso", "local", "token", "certificate", "none");

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Catalog(List<SystemEntry> systems, List<Flow> flows) {
        public Catalog {
            systems = systems != null ? List.copyOf(systems) : List.of();
            flows = flows != null ? List.copyOf(flows) : List.of();
        }
    }

    /**
     * One system a developer may need: an app, a platform service, a piece of infrastructure or an agent.
     *
     * @param kind        a built-in kind (harbor, keycloak, kubernetes, mcp-agent, ...) that brings an icon,
     *                    a category and a usage guide; {@code generic} for anything else
     * @param category    overrides the kind's category (security, delivery, platform, ...)
     * @param environment lab, dev, staging, prod ...
     * @param healthUrl   probed for status; defaults to the first health, ui, api or mcp link
     * @param insecureTls accept self-signed certificates when probing
     * @param access      how to log in; never the password itself
     * @param usage       markdown shown above the kind's built-in guide (instance-specific how-to)
     * @param source      {@code manual}, {@code seed} or {@code orchestrator} (kept in sync automatically)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SystemEntry(String id, String name, String kind, String category, String environment,
                              String description, String owner, List<String> tags, List<Link> links,
                              String healthUrl, Boolean insecureTls, Access access, List<Dependency> dependsOn,
                              String usage, String notes, String source) {
        public SystemEntry {
            id = trim(id);
            name = trim(name);
            kind = trim(kind) == null ? "generic" : trim(kind).toLowerCase();
            category = trim(category);
            environment = trim(environment);
            description = trim(description);
            owner = trim(owner);
            tags = tags == null ? List.of() : tags.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
            links = links == null ? List.of() : links.stream().filter(l -> l != null && l.url() != null).toList();
            healthUrl = trim(healthUrl);
            insecureTls = insecureTls != null && insecureTls ? Boolean.TRUE : null;
            dependsOn = dependsOn == null ? List.of() : dependsOn.stream().filter(d -> d != null && d.to() != null).toList();
            usage = trim(usage);
            notes = trim(notes);
            source = trim(source) == null ? "manual" : trim(source);
        }

        public boolean tlsInsecure() {
            return Boolean.TRUE.equals(insecureTls);
        }

        public SystemEntry withId(String newId) {
            return new SystemEntry(newId, name, kind, category, environment, description, owner, tags, links,
                    healthUrl, insecureTls, access, dependsOn, usage, notes, source);
        }

        public SystemEntry withDependsOn(List<Dependency> deps) {
            return new SystemEntry(id, name, kind, category, environment, description, owner, tags, links,
                    healthUrl, insecureTls, access, deps, usage, notes, source);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(String label, String url, String type) {
        public Link {
            label = trim(label);
            url = trim(url);
            type = trim(type) == null ? "ui" : trim(type).toLowerCase();
        }
    }

    /**
     * @param method        sso (Keycloak), local, token, certificate, none
     * @param username      user name or a hint ("your Keycloak account")
     * @param credentialRef where the secret lives, e.g. "Vaultwarden: Lab / Grafana admin"; never the secret
     * @param howTo         one or two lines on getting access
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Access(String method, String username, String credentialRef, String howTo) {
        public Access {
            method = trim(method);
            username = trim(username);
            credentialRef = trim(credentialRef);
            howTo = trim(howTo);
        }
    }

    /** This system calls or relies on {@code to}; {@code label} says how ("OIDC login", "pulls images"). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dependency(String to, String label) {
        public Dependency {
            to = trim(to);
            label = trim(label);
        }
    }

    /** A named end-to-end path through the systems, e.g. "Ship a change" or "Sign in with SSO". */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flow(String id, String name, String description, List<FlowStep> steps) {
        public Flow {
            id = trim(id);
            name = trim(name);
            description = trim(description);
            steps = steps == null ? List.of() : steps.stream().filter(s -> s != null).toList();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlowStep(String from, String to, String text) {
        public FlowStep {
            from = trim(from);
            to = trim(to);
            text = trim(text);
        }
    }

    static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
