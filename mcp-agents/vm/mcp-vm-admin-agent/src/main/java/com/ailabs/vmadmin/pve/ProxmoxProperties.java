package com.ailabs.vmadmin.pve;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code vmadmin.proxmox:} section of config.yml: the Proxmox VE cluster the VMs run on.
 *
 * <pre>
 * vmadmin:
 *   proxmox:
 *     endpoints: [https://192.168.1.30:8006, https://192.168.1.40:8006]   # any cluster nodes, tried in order
 *     token-ref: path:~/.vm-admin/proxmox.token   # file holds  user@realm!tokenid=secret
 *     ca-cert-ref: path:~/.vm-admin/pve-root-ca.pem   # or insecure-skip-tls-verify: true
 * </pre>
 *
 * @param endpoints              base URLs of one or more cluster nodes; the first that answers is used
 * @param tokenRef               API token as {@code user@realm!tokenid=secret}: store:, env:, path: or vault:
 * @param insecureSkipTlsVerify  accept any certificate (Proxmox's default certificate is self-signed)
 * @param caCertRef              {@code path:} to the cluster CA ({@code /etc/pve/pve-root-ca.pem} on any node)
 * @param timeout                per request
 * @param ipCacheTtl             how long guest IP addresses (one API call per guest) are reused
 */
@ConfigurationProperties(prefix = "vmadmin.proxmox")
public record ProxmoxProperties(List<String> endpoints, String tokenRef, Boolean insecureSkipTlsVerify,
                                String caCertRef, Duration timeout, Duration ipCacheTtl) {

    public ProxmoxProperties {
        endpoints = endpoints == null ? List.of() : endpoints.stream()
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("/+$", "").replaceAll("/api2/json$", ""))
                .distinct().toList();
        for (String e : endpoints) {
            URI u = URI.create(e);
            if (!"https".equals(u.getScheme()) || u.getHost() == null) {
                throw new IllegalArgumentException("vmadmin.proxmox.endpoints must be https://host:8006, got '" + e + "'");
            }
        }
        tokenRef = tokenRef == null || tokenRef.isBlank() ? null : tokenRef.trim();
        insecureSkipTlsVerify = insecureSkipTlsVerify != null && insecureSkipTlsVerify;
        caCertRef = caCertRef == null || caCertRef.isBlank() ? null : caCertRef.trim();
        if (caCertRef != null && !caCertRef.startsWith("path:")) {
            throw new IllegalArgumentException("vmadmin.proxmox.ca-cert-ref must be a path: reference");
        }
        timeout = timeout != null ? timeout : Duration.ofSeconds(8);
        ipCacheTtl = ipCacheTtl != null ? ipCacheTtl : Duration.ofMinutes(2);
    }

    public boolean configured() {
        return !endpoints.isEmpty() && tokenRef != null;
    }
}
