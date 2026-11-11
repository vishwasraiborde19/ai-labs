package com.ailabs.vmadmin.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ailabs.vmadmin.config.AdminProperties;
import com.ailabs.vmadmin.fleet.FleetService;
import com.ailabs.vmadmin.fleet.FleetStore;
import com.ailabs.vmadmin.fleet.Host;
import com.ailabs.vmadmin.metrics.MetricsService;

class AlertServiceTest {

    @TempDir
    Path dir;

    private AlertService service() {
        FleetStore store = new FleetStore(dir.resolve("fleet.yml"));
        if (store.find("web-01").isEmpty()) store.upsert(new Host("web-01", "http://10.0.0.1:8765", "env:X", null, null, false, false, null, null), null, null);
        AdminProperties.Alerts a = new AdminProperties.Alerts(null, null, Duration.ZERO, 90, 95, 2.0, true, false);
        return new AlertService(new AdminProperties(null, null, null, null, null, null, null, a), store);
    }

    private static FleetService.Status status(String state) {
        return new FleetService.Status("web-01", state, "2.2.0", List.of(), null, null, Instant.now(), state.equals("online") ? null : "down", null, null, null);
    }

    @Test
    void firesAndResolvesOnThresholds() {
        AlertService s = service();
        s.onSample("web-01", new MetricsService.Sample(0, 9.0, 2, 50.0, 93.5, null, 0, false));
        assertEquals(List.of("disk", "load"), s.active().stream().map(AlertService.Alert::kind).sorted().toList());
        s.onSample("web-01", new MetricsService.Sample(0, 0.5, 2, 50.0, 40.0, null, 2, true));
        assertEquals(List.of("failed-units"), s.active().stream().map(AlertService.Alert::kind).toList(),
                "disk and load resolved; reboot alerts are off by default");
        assertTrue(s.history().stream().anyMatch(x -> x.kind().equals("disk") && x.resolvedAt() != null));
    }

    @Test
    void offlineAfterGracePeriod() {
        AlertService s = service();
        s.onStatus(new FleetService.StatusEvent(null, status("offline")));
        assertEquals(1, s.active().size());
        s.onStatus(new FleetService.StatusEvent(null, status("online")));
        assertTrue(s.active().isEmpty());
    }

    @Test
    void openAlertsSurviveARestartWithoutFiringAgain() {
        AlertService before = service();
        before.onSample("web-01", new MetricsService.Sample(0, 0.1, 2, 50.0, 93.5, null, 0, false));
        assertEquals(1, before.active().size());
        int historyBefore = before.history().size();

        AlertService after = service();  // same fleet.yml directory, as after a restart
        assertEquals(List.of("disk"), after.active().stream().map(AlertService.Alert::kind).toList());
        after.onSample("web-01", new MetricsService.Sample(0, 0.1, 2, 50.0, 93.5, null, 0, false));
        assertEquals(historyBefore, after.history().size(), "still-open alert is not raised a second time");

        after.onSample("web-01", new MetricsService.Sample(0, 0.1, 2, 50.0, 40.0, null, 0, false));
        assertTrue(after.active().isEmpty());
        assertTrue(after.history().get(0).resolvedAt() != null, "resolved after the restart");
        assertTrue(service().active().isEmpty(), "resolution is saved too");
    }
}
