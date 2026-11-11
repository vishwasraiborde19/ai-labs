package com.ailabs.vmadmin.fleet;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One VM running mcp-vm-worker-agent.
 *
 * @param tokenRef where its bearer token comes from: {@code store:<name>} (pasted in the UI, kept in
 *                 tokens.yml), {@code env:VAR}, {@code path:/file} or {@code vault:path#field}
 * @param readOnly the control centre may browse and read, but not run commands, change files or upgrade
 * @param caCertRef {@code path:} to a PEM CA for agents installed with {@code --tls}
 */
public record Host(String name, String url, String tokenRef, String group, List<String> tags,
                   boolean readOnly, boolean insecureSkipTlsVerify, String caCertRef, String notes) {

    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");
    static final Pattern TAG = Pattern.compile("^[A-Za-z0-9._-]{1,40}$");

    public Host {
        name = trim(name);
        url = trim(url);
        if (url != null && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        tokenRef = trim(tokenRef);
        group = trim(group);
        tags = tags != null ? List.copyOf(tags.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList()) : List.of();
        caCertRef = trim(caCertRef);
        notes = trim(notes);
    }

    /** Problems with this entry, empty when valid. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            p.add("name must be lowercase letters, digits, '.', '_' or '-' (max 63), got '" + name + "'");
        }
        if (url == null) {
            p.add(name + ": url is required");
        } else {
            try {
                URI u = URI.create(url);
                if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null) {
                    p.add(name + ": url must be http(s)://host:port");
                }
            } catch (IllegalArgumentException e) {
                p.add(name + ": url is not valid");
            }
        }
        if (tokenRef == null) {
            p.add(name + ": a token is required");
        }
        if (group != null && !TAG.matcher(group).matches()) {
            p.add(name + ": group may use letters, digits, '.', '_' and '-'");
        }
        for (String t : tags) {
            if (!TAG.matcher(t).matches()) {
                p.add(name + ": bad tag '" + t + "'");
            }
        }
        if (caCertRef != null && !caCertRef.startsWith("path:")) {
            p.add(name + ": ca-cert-ref must be path:/file.pem");
        }
        return p;
    }

    public Host withTokenRef(String ref) {
        return new Host(name, url, ref, group, tags, readOnly, insecureSkipTlsVerify, caCertRef, notes);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
