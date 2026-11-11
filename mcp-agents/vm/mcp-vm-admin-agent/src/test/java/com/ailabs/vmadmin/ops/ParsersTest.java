package com.ailabs.vmadmin.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ailabs.vmadmin.metrics.MetricsServiceTestAccess;
import com.ailabs.vmadmin.schedule.ScheduleService;
import com.ailabs.vmadmin.sync.Diff;

class ParsersTest {

    @Test
    void parsesSystemdServices() {
        String out = """
                nginx.service                 loaded active   running A high performance web server
                cron.service                  loaded active   running Regular background program processing daemon
                apt-daily.service             loaded inactive dead    Daily apt download activities
                broken.service                loaded failed   failed  Broken thing
                @@FILES@@
                nginx.service                  enabled  enabled
                cron.service                   enabled  enabled
                apt-daily.service              static   -
                getty@.service                 enabled  enabled
                ssh.service                    disabled enabled
                """;
        List<Map<String, String>> s = HostAdmin.parseServices(out);
        assertEquals(List.of("apt-daily.service", "broken.service", "cron.service", "nginx.service", "ssh.service"),
                s.stream().map(m -> m.get("unit")).toList());
        Map<String, String> nginx = s.get(3);
        assertEquals("active", nginx.get("active"));
        assertEquals("enabled", nginx.get("enabled"));
        assertEquals("A high performance web server", nginx.get("description"));
        assertEquals("inactive", s.get(4).get("active"), "unit file only: shown as inactive");
        assertEquals("disabled", s.get(4).get("enabled"));
    }

    @Test
    void parsesUpgradablePackages() {
        Map<String, Object> apt = HostAdmin.parseUpgradable("""
                PM=apt
                curl/jammy-updates 7.81.0-1ubuntu1.20 amd64 [upgradable from: 7.81.0-1ubuntu1.18]
                libssl3/jammy-security 3.0.2-0ubuntu1.18 amd64 [upgradable from: 3.0.2-0ubuntu1.15]
                """);
        assertEquals("apt", apt.get("manager"));
        List<?> pk = (List<?>) apt.get("packages");
        assertEquals(2, pk.size());
        assertEquals(Map.of("name", "curl", "repo", "jammy-updates", "version", "7.81.0-1ubuntu1.20", "current", "7.81.0-1ubuntu1.18"), pk.get(0));

        Map<String, Object> dnf = HostAdmin.parseUpgradable("PM=dnf\n\nkernel.x86_64   5.14.0-427.el9   baseos\nObsoleting Packages\n");
        assertEquals(1, ((List<?>) dnf.get("packages")).size());
        assertEquals("none", HostAdmin.parseUpgradable("PM=none\n").get("manager"));
    }

    @Test
    void quotesAndValidatesShellInputs() {
        assertEquals("'it'\\''s'", HostAdmin.sq("it's"));
        assertTrue(HostAdmin.UNIT.matcher("nginx.service").matches());
        assertTrue(HostAdmin.UNIT.matcher("getty@tty1.service").matches());
        assertFalse(HostAdmin.UNIT.matcher("x; rm -rf /").matches());
        assertFalse(HostAdmin.PKG.matcher("curl && reboot").matches());
        assertFalse(HostAdmin.CURSOR.matcher("s=1';reboot;'").matches());
    }

    @Test
    void diffsLikeUnifiedDiff() {
        String a = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        String b = "a\nb\nc\nD\ne\nf\ng\nh\ni\nj\nk\n";
        String d = Diff.unified("old", "new", a, b);
        assertTrue(d.startsWith("--- old\n+++ new\n"), d);
        assertTrue(d.contains("-d\n+D\n"), d);
        assertTrue(d.contains("+k\n"), d);
        assertTrue(d.contains("@@ -1,"), d);
        assertTrue(Diff.unified("x", "y", "same\n", "same\n").contains("(no differences)"));
        assertTrue(Diff.unified("/dev/null", "f", "", "one\ntwo\n").contains("+one\n+two\n"));
    }

    @Test
    void cronAcceptsFiveAndSixFieldsAndMacros() {
        assertTrue(ScheduleService.next(ScheduleService.parseCron("0 3 * * *")) != null);
        assertTrue(ScheduleService.next(ScheduleService.parseCron("*/15 * * * * *")) != null);
        assertTrue(ScheduleService.next(ScheduleService.parseCron("@hourly")) != null);
        assertThrows(IllegalArgumentException.class, () -> ScheduleService.parseCron("every day"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleService.parseCron(""));
    }

    @Test
    void metricsSampleFromSysinfo() {
        var s = MetricsServiceTestAccess.parse(Map.of("mem_total_kb", "1000", "mem_avail_kb", "250", "disk_root", "200 150 50",
                "load", "1.50 1.00 0.50", "cpus", "2", "swap_total_kb", "0", "swap_free_kb", "0", "failed_units", "1",
                "reboot_required", "no"));
        assertEquals(75.0, s.memPct());
        assertEquals(75.0, s.diskPct());
        assertEquals(1.5, s.load1());
        assertEquals(2, s.cpus());
        assertNull(s.swapPct(), "no swap");
        assertEquals(1, s.failedUnits());
        assertFalse(s.rebootRequired());
        var empty = MetricsServiceTestAccess.parse(Map.of("cpus", "", "disk_root", ""));
        assertNull(empty.cpus());
        assertNull(empty.diskPct());
    }
}
