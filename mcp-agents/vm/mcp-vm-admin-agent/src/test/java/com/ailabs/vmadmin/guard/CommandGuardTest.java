package com.ailabs.vmadmin.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ailabs.vmadmin.config.AdminProperties;

class CommandGuardTest {

    private final CommandGuard guard = new CommandGuard(new AdminProperties(null, null, null, null,
            new AdminProperties.Guard(List.of("\\bterraform\\s+destroy\\b")), null, null, null));

    @ParameterizedTest
    @ValueSource(strings = {
            "rm -rf /", "sudo rm -rf / ", "rm -fr /*", "rm -rf /etc", "rm -r -f /var/", "cd x && rm -Rf /usr; ls",
            "rm -rf --no-preserve-root /", "mkfs.ext4 /dev/sdb1", "dd if=/dev/zero of=/dev/sda bs=1M", "echo x > /dev/nvme0n1",
            "reboot", "sudo shutdown -h now", "systemctl reboot", "init 6", "systemctl stop nginx", "sudo systemctl disable docker",
            ":(){ :|:& };:", "chmod -R 777 /", "userdel bob", "sudo passwd root", "echo 'a:b' | chpasswd",
            "iptables -F", "ufw disable", "apt-get -y purge nginx", "dnf remove httpd", "kill -9 -1",
            "psql -c 'DROP TABLE users'", "docker system prune -af", "kubectl delete ns prod",
            "rm -rf ~/mcp-vm-installer-agent", "terraform destroy -auto-approve"})
    void flagsRiskyCommands(String cmd) {
        assertTrue(guard.check(cmd).isPresent(), cmd);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ls -la /", "df -h", "rm -rf ./build", "rm -rf /tmp/work/build", "rm file.txt", "cat /etc/passwd",
            "systemctl status nginx", "systemctl restart nginx", "journalctl -u nginx -n 50", "docker ps -a",
            "kubectl get pods -A", "apt list --upgradable", "echo reboot-required", "uptime", "grep passwd /etc/nsswitch.conf"})
    void letsOrdinaryCommandsThrough(String cmd) {
        assertFalse(guard.check(cmd).isPresent(), cmd);
    }
}
