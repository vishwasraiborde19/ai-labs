package com.ailabs.vmadmin.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code vmadmin:} section of {@code ~/.vm-admin/config.yml}. The fleet itself (hosts, groups)
 * lives in {@code fleet.yml}, edited from the UI.
 */
@ConfigurationProperties(prefix = "vmadmin")
public record AdminProperties(Ui ui, Mcp mcp, Vault vault, Status status, Guard guard, List<Snippet> snippets,
                              Metrics metrics, Alerts alerts) {

    public AdminProperties {
        ui = ui != null ? ui : new Ui(null);
        mcp = mcp != null ? mcp : new Mcp(null);
        status = status != null ? status : new Status(null, null);
        guard = guard != null ? guard : new Guard(null);
        snippets = snippets != null && !snippets.isEmpty() ? List.copyOf(snippets) : Snippet.DEFAULTS;
        metrics = metrics != null ? metrics : new Metrics(null, null);
        alerts = alerts != null ? alerts : new Alerts(null, null, null, null, null, null, null, null);
    }

    /** How often load, memory and disk are sampled per host, and how long samples are kept. */
    public record Metrics(Duration interval, Duration retention) {
        public Metrics {
            interval = interval != null ? interval : Duration.ofMinutes(5);
            retention = retention != null ? retention : Duration.ofDays(7);
        }
    }

    /**
     * When to raise an alert, and where to send it.
     *
     * @param webhookRef env: or path: holding the webhook URL (Slack, Teams, Mattermost or any JSON endpoint)
     * @param format     {@code slack} ({"text": ...}, also understood by Mattermost and Teams workflows) or {@code json}
     */
    public record Alerts(String webhookRef, String format, Duration offlineAfter, Integer diskPct, Integer memPct,
                         Double loadPerCpu, Boolean failedUnits, Boolean rebootRequired) {
        public Alerts {
            format = format == null || format.isBlank() ? "slack" : format.trim().toLowerCase();
            if (!List.of("slack", "json").contains(format)) {
                throw new IllegalArgumentException("vmadmin.alerts.format must be slack or json");
            }
            offlineAfter = offlineAfter != null ? offlineAfter : Duration.ofMinutes(2);
            diskPct = diskPct != null ? diskPct : 90;
            memPct = memPct != null ? memPct : 95;
            loadPerCpu = loadPerCpu != null ? loadPerCpu : 2.0;
            failedUnits = failedUnits == null || failedUnits;
            rebootRequired = rebootRequired != null && rebootRequired;
        }
    }

    /**
     * @param auth {@code none} (default): no sign-in, the UI accepts clients on this machine only
     *             (use an SSH tunnel from elsewhere). {@code saml}: reserved, not built yet.
     */
    public record Ui(String auth) {
        public static final List<String> AUTH_MODES = List.of("none", "saml");

        public Ui {
            auth = auth == null || auth.isBlank() ? "none" : auth.trim().toLowerCase();
            if (!AUTH_MODES.contains(auth)) {
                throw new IllegalArgumentException("vmadmin.ui.auth must be one of " + AUTH_MODES + ", got '" + auth + "'");
            }
        }
    }

    /** Bearer token MCP clients must send to {@code /mcp}; unset disables MCP. env: or path: only. */
    public record Mcp(String tokenRef) {}

    /** Optional HashiCorp Vault (KV v2) for {@code vault:path#field} token refs. */
    public record Vault(String address, String tokenRef, String kvMount, String namespace) {
        public Vault {
            kvMount = kvMount == null || kvMount.isBlank() ? "secret" : kvMount;
        }
    }

    /** How often the fleet dashboard re-checks every host, and how long a check may take. */
    public record Status(Duration interval, Duration timeout) {
        public Status {
            interval = interval != null ? interval : Duration.ofSeconds(30);
            timeout = timeout != null ? timeout : Duration.ofSeconds(5);
        }
    }

    /** Extra regexes (on top of the built-in list) that make a command need explicit confirmation. */
    public record Guard(List<String> confirmPatterns) {
        public Guard {
            confirmPatterns = confirmPatterns != null ? List.copyOf(confirmPatterns) : List.of();
        }
    }

    /** A one-click command in the UI. */
    public record Snippet(String name, String command) {
        public static final List<Snippet> DEFAULTS = List.of(
                new Snippet("Disk usage", "df -h -x tmpfs -x devtmpfs -x squashfs"),
                new Snippet("Memory", "free -h"),
                new Snippet("Top CPU", "ps -eo pid,user,%cpu,%mem,etime,cmd --sort=-%cpu | head -15"),
                new Snippet("Top memory", "ps -eo pid,user,%cpu,%mem,etime,cmd --sort=-%mem | head -15"),
                new Snippet("Failed services", "systemctl --failed --no-pager"),
                new Snippet("Listening ports", "ss -tlnp 2>/dev/null || netstat -tlnp"),
                new Snippet("Recent errors", "journalctl -p err -n 50 --no-pager 2>&1 | tail -50"),
                new Snippet("Docker containers", "docker ps -a --format 'table {{.Names}}\\t{{.Image}}\\t{{.Status}}'"),
                new Snippet("Pending updates", "(apt list --upgradable 2>/dev/null || dnf check-update) | head -40"),
                new Snippet("Logged-in users", "who; echo; last -n 10"));
    }

    /** {@code VMADMIN_HOME}, default {@code ~/.vm-admin}. */
    public static Path home() {
        String h = System.getenv("VMADMIN_HOME");
        return h != null && !h.isBlank() ? Path.of(h) : Path.of(System.getProperty("user.home"), ".vm-admin");
    }
}
