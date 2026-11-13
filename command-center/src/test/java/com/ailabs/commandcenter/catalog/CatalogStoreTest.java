package com.ailabs.commandcenter.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ailabs.commandcenter.catalog.Model.Access;
import com.ailabs.commandcenter.catalog.Model.Dependency;
import com.ailabs.commandcenter.catalog.Model.Flow;
import com.ailabs.commandcenter.catalog.Model.FlowStep;
import com.ailabs.commandcenter.catalog.Model.Link;
import com.ailabs.commandcenter.catalog.Model.SystemEntry;

class CatalogStoreTest {

    @TempDir
    Path dir;

    KindRegistry kinds = new KindRegistry();
    CatalogStore store;

    @BeforeEach
    void setUp() {
        store = new CatalogStore(dir.resolve("catalog.yml"), kinds);
    }

    static SystemEntry sys(String id, String kind, List<Dependency> deps) {
        return new SystemEntry(id, id.toUpperCase(), kind, null, "lab", null, null, List.of(),
                List.of(new Link("UI", "http://" + id + ".lan", "ui")), null, null, null, deps, null, null, null);
    }

    @Test
    void seedCatalogIsValidAndWritten() {
        assertTrue(Files.exists(store.file()));
        var c = store.snapshot().catalog();
        assertTrue(c.systems().size() > 10, "seed has the lab systems");
        assertTrue(c.flows().size() >= 4, "seed has flows");
        assertEquals(List.of(), store.problems(c));
    }

    @Test
    void everyKindHasAGuideAndAKnownCategory() {
        for (KindRegistry.Kind k : kinds.kinds()) {
            assertNotNull(k.guide(), k.id() + " has a guide");
            assertTrue(kinds.knownCategory(k.category()), k.id());
            assertNotNull(k.abbr(), k.id());
        }
        assertEquals("generic", kinds.kind("no-such-kind").id());
    }

    @Test
    void addEditRenameAndDeleteKeepReferencesConsistent() {
        long v = store.snapshot().version();
        store.saveSystem(sys("svc-a", "spring-boot-app", List.of(new Dependency("keycloak", "login"))), null, v);
        store.saveFlow(new Flow("f1", "Flow", null, List.of(new FlowStep("svc-a", "keycloak", "logs in"))), null, null);

        // rename keycloak -> idp: deps and flow steps follow
        SystemEntry kc = store.require("keycloak");
        store.saveSystem(kc.withId("idp"), "keycloak", null);
        assertTrue(store.find("keycloak").isEmpty());
        assertEquals("idp", store.require("svc-a").dependsOn().get(0).to());
        Flow f1 = store.snapshot().catalog().flows().stream().filter(f -> f.id().equals("f1")).findFirst().orElseThrow();
        assertEquals("idp", f1.steps().get(0).to());

        // delete idp: deps on it and steps through it disappear
        store.deleteSystem("idp", null);
        assertTrue(store.require("svc-a").dependsOn().isEmpty());
        assertTrue(store.snapshot().catalog().flows().stream().filter(f -> f.id().equals("f1")).findFirst().orElseThrow().steps().isEmpty());
        assertEquals(List.of(), store.problems(store.snapshot().catalog()));
    }

    @Test
    void rejectsDuplicatesStaleVersionsUnknownDepsAndSecrets() {
        assertThrows(CatalogStore.ConflictException.class, () -> store.saveSystem(sys("keycloak", "keycloak", List.of()), null, null));
        assertThrows(CatalogStore.ConflictException.class, () -> store.saveSystem(sys("x1", "generic", List.of()), null, 9999L));
        assertThrows(IllegalArgumentException.class,
                () -> store.saveSystem(sys("x2", "generic", List.of(new Dependency("nope", null))), null, null));
        SystemEntry secret = new SystemEntry("x3", "X3", "generic", null, null, null, null, null, null, null, null,
                new Access("local", "admin", "password: example-only", null), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> store.saveSystem(secret, null, null));
        SystemEntry badUrl = new SystemEntry("x4", "X4", "generic", null, null, null, null, null,
                List.of(new Link("x", "ftp://host", "ui")), null, null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> store.saveSystem(badUrl, null, null));
    }

    @Test
    void secretHeuristics() {
        assertTrue(CatalogStore.looksLikeSecret("token=abc123"));
        // fabricated, token-shaped string (not a real credential)
        assertTrue(CatalogStore.looksLikeSecret("tok_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4"));
        assertFalse(CatalogStore.looksLikeSecret("Password manager: Lab / Grafana admin"));
        assertFalse(CatalogStore.looksLikeSecret("~/.ai-labs/tokens/devops.token (created by agents.sh)"));
        assertFalse(CatalogStore.looksLikeSecret("vault:kv/lab/grafana#password"));
    }

    @Test
    void idFromNameAndHandEditsAreReloaded() throws Exception {
        SystemEntry noId = new SystemEntry(null, "Harbor Mirror (EU)", "harbor", null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        assertEquals("harbor-mirror-eu", store.saveSystem(noId, null, null).id());

        String yaml = Files.readString(store.file()).replace("name: Harbor Mirror (EU)", "name: Harbor Mirror");
        Files.writeString(store.file(), yaml);
        Files.setLastModifiedTime(store.file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertEquals("Harbor Mirror", store.require("harbor-mirror-eu").name());
    }

    @Test
    void importMergeAndExportRoundTrip() {
        String exported = store.exportYaml();
        int before = store.snapshot().catalog().systems().size();
        var r = store.importYaml("systems:\n  - {id: extra, name: Extra, kind: generic}\n", false);
        assertEquals("merge", r.get("mode"));
        assertEquals(before + 1, store.snapshot().catalog().systems().size());
        store.importYaml(exported, true);
        assertEquals(before, store.snapshot().catalog().systems().size());
        assertThrows(IllegalArgumentException.class, () -> store.importYaml("systems: [ {", false));
    }
}
