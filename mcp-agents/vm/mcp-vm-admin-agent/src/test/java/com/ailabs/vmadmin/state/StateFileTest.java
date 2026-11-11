package com.ailabs.vmadmin.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ailabs.vmadmin.fleet.FleetService;
import com.fasterxml.jackson.core.type.TypeReference;

class StateFileTest {

    @TempDir
    Path dir;

    private StateFile<Map<String, FleetService.Status>> file() {
        return StateFile.beside(dir.resolve("fleet.yml"), "status.json", new TypeReference<Map<String, FleetService.Status>>() {});
    }

    @Test
    void roundTripsHostStatus() throws Exception {
        Instant t = Instant.parse("2026-11-11T10:15:30Z");
        FleetService.Status s = new FleetService.Status("web-01", "online", "2.2.0", List.of("fs", "pty"), 3600L, 12L, t,
                null, "/home/vm", "/home/vm/mcp-vm-worker-agent", "web-01");
        file().write(Map.of("web-01", s));

        assertTrue(Files.exists(dir.resolve("state/status.json")));
        assertEquals(s, file().read().get("web-01"));
        assertTrue(Files.readString(dir.resolve("state/status.json")).contains("2026-11-11T10:15:30Z"), "ISO timestamps");
    }

    @Test
    void missingOrCorruptFileReadsAsNull() throws Exception {
        assertNull(file().read());
        Files.createDirectories(dir.resolve("state"));
        Files.writeString(dir.resolve("state/status.json"), "{not json");
        assertNull(file().read());
        assertTrue(Files.exists(dir.resolve("state/status.json.corrupt")));
    }
}
