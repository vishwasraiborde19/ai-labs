package com.ailabs.commandcenter.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ailabs.commandcenter.catalog.Model.Access;
import com.ailabs.commandcenter.catalog.Model.SystemEntry;
import com.fasterxml.jackson.databind.ObjectMapper;

class OrchestratorSyncTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SystemEntry orchestrator() {
        return new SystemEntry("orchestrator", "Orchestrator", "orchestrator", null, null, null, null, null, null,
                null, null, null, null, null, null, "seed");
    }

    @Test
    void mapsAnAgentView() throws Exception {
        var view = JSON.readTree("""
                {"agent":{"id":"kube","name":"Kube agent","role":"Kubernetes operator","tags":["k8s"],
                          "transport":"http","url":"http://127.0.0.1:8768/mcp","tokenRef":"path:~/.ai-labs/tokens/kube.token"},
                 "state":{"status":"online"}}""");
        SystemEntry e = OrchestratorSync.toEntry(view);
        assertEquals("agent-kube", e.id());
        assertEquals("mcp-agent", e.kind());
        assertEquals("http://127.0.0.1:8768/mcp", e.links().get(0).url());
        assertEquals("mcp", e.links().get(0).type());
        assertTrue(e.tags().contains("agent"));
        assertEquals("orchestrator", e.source());

        var stdio = JSON.readTree("{\"agent\":{\"id\":\"local\",\"transport\":\"stdio\",\"command\":[\"npx\",\"x\"]}}");
        assertTrue(OrchestratorSync.toEntry(stdio).links().isEmpty());
        assertNull(OrchestratorSync.toEntry(JSON.readTree("{\"agent\":{}}")));
    }

    @Test
    void mergeAddsUpdatesKeepsCurationAndRemovesGoneAgents() {
        SystemEntry curated = new SystemEntry("agent-kube", "Old name", "mcp-agent", null, "lab", "old", "platform team",
                List.of(), List.of(), null, null, new Access("token", null, "Password manager: kube token", null),
                List.of(), "Use namespace dev only.", null, "orchestrator");
        SystemEntry gone = new SystemEntry("agent-old", "Old", "mcp-agent", null, null, null, null, null, null, null,
                null, null, null, null, null, "orchestrator");
        SystemEntry manual = new SystemEntry("agent-manual", "Manual", "mcp-agent", null, null, null, null, null, null,
                null, null, null, null, null, null, "manual");

        SystemEntry kubeIn = new SystemEntry("agent-kube", "Kube agent", "mcp-agent", "agents", "lab", "Kubernetes operator",
                null, List.of("agent"), List.of(), null, null, null, List.of(), null, null, "orchestrator");
        SystemEntry newIn = new SystemEntry("agent-devops", "DevOps", "mcp-agent", "agents", "lab", null,
                null, List.of("agent"), List.of(), null, null, null, List.of(), null, null, "orchestrator");

        int[] ch = new int[3];
        List<SystemEntry> out = OrchestratorSync.merge(List.of(orchestrator(), curated, gone, manual), List.of(kubeIn, newIn), ch);

        assertEquals(1, ch[0]);
        assertEquals(1, ch[1]);
        assertEquals(1, ch[2]);
        assertTrue(out.stream().noneMatch(s -> s.id().equals("agent-old")));
        assertTrue(out.stream().anyMatch(s -> s.id().equals("agent-manual")), "manual entries are never removed");
        SystemEntry kube = out.stream().filter(s -> s.id().equals("agent-kube")).findFirst().orElseThrow();
        assertEquals("Kube agent", kube.name());
        assertEquals("platform team", kube.owner());
        assertEquals("Use namespace dev only.", kube.usage());
        assertEquals("Password manager: kube token", kube.access().credentialRef());
        assertEquals("orchestrator", kube.dependsOn().get(0).to());
        SystemEntry devops = out.stream().filter(s -> s.id().equals("agent-devops")).findFirst().orElseThrow();
        assertEquals("orchestrator", devops.dependsOn().get(0).to());

        int[] again = new int[3];
        OrchestratorSync.merge(out, List.of(kubeIn, newIn), again);
        assertEquals(0, again[0] + again[1] + again[2], "a second sync changes nothing");
    }
}
