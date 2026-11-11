package com.ailabs.vmadmin.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.ailabs.vmadmin.config.AdminProperties;

/**
 * Flags commands that can take a VM down or destroy data, so the UI asks for explicit confirmation
 * (and MCP callers must pass {@code confirm=true}). This is a speed bump against slips, not a sandbox:
 * every agent token is a shell on the VM.
 */
@Component
public class CommandGuard {

    public record Rule(String reason, Pattern pattern) {}

    static final List<Rule> BUILT_IN = List.of(
            rule("deletes recursively from / or a top-level directory",
                    "\\brm\\s+(-[a-zA-Z]*\\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\\s+(-[a-zA-Z]+\\s+)*(--no-preserve-root\\s+)?/(\\*|[a-z0-9]+/?)?(\\s|$|;|&)"),
            rule("deletes with --no-preserve-root", "--no-preserve-root"),
            rule("formats or overwrites a disk", "\\bmkfs(\\.\\w+)?\\b|\\bwipefs\\b|\\bdd\\b[^|;&]*\\bof=/dev/|>\\s*/dev/(sd|nvme|vd|xvd|hd)"),
            rule("shuts down or reboots the VM", "(^|[;&|(]\\s*|sudo\\s+)(shutdown|reboot|poweroff|halt)(\\s|$|;)|\\binit\\s+[06]\\b|systemctl\\s+(reboot|poweroff|halt|kexec)"),
            rule("stops or disables a service", "\\bsystemctl\\s+(stop|disable|mask|kill)\\b|\\bservice\\s+\\S+\\s+stop\\b"),
            rule("fork bomb", ":\\s*\\(\\s*\\)\\s*\\{"),
            rule("opens permissions recursively", "\\bchmod\\s+(-[a-zA-Z]*R[a-zA-Z]*\\s+)\\S*7{3}\\b|\\bchown\\s+-[a-zA-Z]*R[a-zA-Z]*\\s+\\S+\\s+/(\\s|$)"),
            rule("removes users or changes passwords", "\\b(userdel|deluser)\\b|(^|[;&|(]\\s*|sudo\\s+)(passwd|chpasswd)(\\s|$)"),
            rule("flushes or disables the firewall", "\\b(iptables|ip6tables)\\s+(-F|--flush)\\b|\\bufw\\s+(disable|reset)\\b|\\bnft\\s+flush\\b"),
            rule("removes packages", "\\b(apt|apt-get|dnf|yum)\\s+(-y\\s+)?(remove|purge|autoremove|erase)\\b"),
            rule("kills processes broadly", "\\bkill(all)?\\s+-9\\s+-1\\b|\\bpkill\\s+-9\\b"),
            rule("drops a database", "(?i)\\bdrop\\s+(database|schema|table)\\b"),
            rule("deletes containers, volumes or images in bulk", "\\bdocker\\s+(system\\s+prune|volume\\s+prune|rm\\s+-f\\s+\\$\\(|rmi\\s+-f\\s+\\$\\()"),
            rule("deletes Kubernetes resources", "\\bkubectl\\s+delete\\b"),
            rule("changes the agent itself", "mcp-vm-(installer|worker)-agent|tokens\\.json|admin\\.json"));

    private final List<Rule> rules;

    public CommandGuard(AdminProperties props) {
        List<Rule> r = new ArrayList<>(BUILT_IN);
        for (String p : props.guard().confirmPatterns()) {
            r.add(rule("matches custom pattern " + p, p));
        }
        this.rules = List.copyOf(r);
    }

    /** Why this command needs confirmation, or empty if it does not. */
    public Optional<String> check(String command) {
        if (command == null) {
            return Optional.empty();
        }
        for (Rule r : rules) {
            if (r.pattern().matcher(command).find()) {
                return Optional.of(r.reason());
            }
        }
        return Optional.empty();
    }

    private static Rule rule(String reason, String regex) {
        return new Rule(reason, Pattern.compile(regex));
    }
}
