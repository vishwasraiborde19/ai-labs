package com.ailabs.orchestrator.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class SystemsTest {

    @Test
    void presetsFollowTools() {
        List<SystemProbe> kube = Presets.forTools(Set.of("k8s_clusters", "k8s_whoami", "k8s_nodes"));
        assertEquals(1, kube.size());
        assertEquals("kubernetes", kube.get(0).kind);
        assertEquals("k8s_whoami", kube.get(0).healthTool);
        assertEquals("cluster", kube.get(0).healthArg);
        assertEquals(List.of("k8s_nodes"), kube.get(0).actions.stream().map(a -> a.tool).toList());

        List<SystemProbe> devops = Presets.forTools(Set.of("devops_list_resources"));
        assertEquals(List.of("jenkins", "dockerhub", "kubernetes"), devops.stream().map(p -> p.kind).toList());
        assertTrue(Presets.forTools(Set.of("something_else")).isEmpty());
    }

    @Test
    void sameEndpointIsSameSystem() {
        assertEquals(SystemsService.key("kubernetes", "https://10.0.0.5:6443/", "prod"),
                SystemsService.key("kubernetes", "HTTPS://10.0.0.5:6443", "proxmox01"));
        assertEquals("vm|name:web-01", SystemsService.key("vm", null, "web-01"));
    }

    @Test
    void walksListPathAndSummarizesHealth() throws Exception {
        var root = new ObjectMapper().readTree("{\"jenkins\":[{\"name\":\"main\"}],\"kubernetes\":[]}");
        assertTrue(SystemsService.at(root, "jenkins").isArray());
        assertEquals("main", SystemsService.at(root, "jenkins").get(0).path("name").asText());
        assertEquals("v1.30.2 · system:serviceaccount:kube-agent:kube-agent",
                SystemsService.summary("{\"cluster\":\"p\",\"version\":\"v1.30.2\",\"user\":\"system:serviceaccount:kube-agent:kube-agent\"}"));
        assertEquals("plain text", SystemsService.summary("plain text"));
    }
}
