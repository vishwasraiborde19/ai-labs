package com.ailabs.orchestrator.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GlobsTest {

    @Test
    void matchesGlobs() {
        List<String> p = List.of("k8s_delete", "*_rollback", "jenkins_?top_build");
        assertTrue(AgentRegistry.Globs.any(p, "k8s_delete"));
        assertTrue(AgentRegistry.Globs.any(p, "k8s_rollback"));
        assertTrue(AgentRegistry.Globs.any(p, "jenkins_stop_build"));
        assertFalse(AgentRegistry.Globs.any(p, "k8s_delete_all"));
        assertFalse(AgentRegistry.Globs.any(p, "k8s.list"));
        assertFalse(AgentRegistry.Globs.any(null, "x"));
    }

    @Test
    void slugAndValidation() {
        assertTrue(AgentRegistry.slug("Kube Agent!").equals("kube-agent"));
        AgentSpec a = new AgentSpec();
        a.id = "kube";
        a.url = "http://127.0.0.1:8767/mcp";
        a.normalize();
        a.validate();
        a.url = "file:///etc/passwd";
        AgentSpec b = a;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, b::validate);
    }
}
