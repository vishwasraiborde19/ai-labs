package com.ailabs.vmadmin.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ailabs.vmadmin.secrets.TokenStore;

class FleetStoreTest {

    @TempDir
    Path dir;

    static Host host(String name) {
        return new Host(name, "http://10.0.0.5:8765/", "store:" + name, "prod", List.of("db", " eu "), false, false, null, null);
    }

    @Test
    void addEditRenameRemove() throws Exception {
        FleetStore s = new FleetStore(dir.resolve("fleet.yml"));
        String v0 = s.snapshot().version();
        String v1 = s.upsert(host("web-01"), null, v0).version();
        assertEquals("http://10.0.0.5:8765", s.require("web-01").url(), "trailing slash trimmed");
        assertEquals(List.of("db", "eu"), s.require("web-01").tags());

        String yml = Files.readString(dir.resolve("fleet.yml"));
        assertTrue(yml.contains("token-ref: \"store:web-01\"") || yml.contains("token-ref: store:web-01"), yml);
        assertFalse(yml.contains("read-only"), "defaults are left out: " + yml);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("fleet.yml"))));

        String v2 = s.upsert(host("web-02"), "web-01", v1).version();
        assertTrue(s.find("web-01").isEmpty());
        assertTrue(s.find("web-02").isPresent());
        assertTrue(Files.exists(dir.resolve("fleet.yml.bak")));

        s.remove("web-02", v2);
        assertTrue(s.snapshot().fleet().hosts().isEmpty());
    }

    @Test
    void rejectsStaleVersionDuplicatesAndBadInput() {
        FleetStore s = new FleetStore(dir.resolve("fleet.yml"));
        String v0 = s.snapshot().version();
        String v1 = s.upsert(host("a"), null, v0).version();
        assertThrows(FleetStore.ConflictException.class, () -> s.upsert(host("b"), null, v0));
        assertThrows(IllegalArgumentException.class, () -> s.upsert(host("a"), null, v1));
        assertThrows(IllegalArgumentException.class, () -> s.upsert(host("Bad Name"), null, v1));
        assertThrows(IllegalArgumentException.class, () -> s.upsert(
                new Host("c", "ftp://x", "env:X", null, null, false, false, null, null), null, v1));
        assertThrows(IllegalArgumentException.class, () -> s.upsert(
                new Host("c", "http://x:1", null, null, null, false, false, null, null), null, v1));
    }

    @Test
    void picksUpHandEdits() throws Exception {
        Path f = dir.resolve("fleet.yml");
        FleetStore s = new FleetStore(f);
        Files.writeString(f, "hosts:\n- name: db-1\n  url: https://db1:8765\n  token-ref: env:DB1\n  read-only: true\n");
        Host h = s.require("db-1");
        assertTrue(h.readOnly());
        assertEquals("env:DB1", h.tokenRef());
    }

    @Test
    void tokenStoreKeepsTokensPrivate() throws Exception {
        TokenStore t = new TokenStore(dir.resolve("tokens.yml"));
        t.put("web-01", "mva_abcdefghijklmnopqrstuvwxyz");
        assertEquals("mva_abcdefghijklmnopqrstuvwxyz", t.get("web-01").orElseThrow());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("tokens.yml"))));
        assertThrows(IllegalArgumentException.class, () -> t.put("x", "has space"));
        t.remove("web-01");
        assertTrue(t.get("web-01").isEmpty());
    }
}
