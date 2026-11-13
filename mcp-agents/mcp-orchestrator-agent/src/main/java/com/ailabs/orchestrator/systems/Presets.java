package com.ailabs.orchestrator.systems;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Built-in system probes, picked when an agent offers the matching tools. */
public final class Presets {

    private Presets() {}

    private static final String SYS = "{{system}}";

    public static List<SystemProbe> forTools(Set<String> tools) {
        List<SystemProbe> out = new ArrayList<>();

        // mcp-kube-agent
        if (tools.contains("k8s_clusters")) {
            SystemProbe p = probe("kubernetes", "k8s_clusters", null);
            if (tools.contains("k8s_whoami")) {
                p.healthTool = "k8s_whoami";
                p.healthArg = "cluster";
            }
            action(p, tools, "Nodes", "k8s_nodes", Map.of("cluster", SYS));
            action(p, tools, "Health sweep", "k8s_troubleshoot", Map.of("cluster", SYS));
            action(p, tools, "Top pods", "k8s_top", Map.of("cluster", SYS, "what", "pods"));
            out.add(p);
        }

        // mcp-devops-agent
        if (tools.contains("devops_list_resources")) {
            SystemProbe j = probe("jenkins", "devops_list_resources", "jenkins");
            action(j, tools, "Jobs", "jenkins_list_jobs", Map.of("jenkins", SYS));
            out.add(j);
            out.add(probe("dockerhub", "devops_list_resources", "dockerhub"));
            SystemProbe k = probe("kubernetes", "devops_list_resources", "kubernetes");
            action(k, tools, "Deployments (default ns)", "k8s_list_deployments", Map.of("cluster", SYS, "namespace", "default"));
            out.add(k);
        }

        // mcp-vm-admin-agent (control centre)
        if (tools.contains("fleet_hosts")) {
            SystemProbe v = probe("vm", "fleet_hosts", null);
            v.stateField = "state";
            action(v, tools, "System info", "host_sysinfo", Map.of("host", SYS));
            out.add(v);
        }
        return out;
    }

    private static SystemProbe probe(String kind, String tool, String path) {
        SystemProbe p = new SystemProbe();
        p.kind = kind;
        p.listTool = tool;
        p.listPath = path;
        return p;
    }

    private static void action(SystemProbe p, Set<String> tools, String label, String tool, Map<String, Object> args) {
        if (tools.contains(tool)) p.actions.add(new SystemProbe.QuickAction(label, tool, args));
    }
}
