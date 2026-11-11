package com.ailabs.devops.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.devops.access.SecretResolver;
import com.ailabs.devops.config.DevopsProperties;
import com.ailabs.devops.config.Systems;

/** Skill: discover configured resources and test that the agent can reach them. */
@Component
public class AccessTools {

    private final Resources resources;
    private final SecretResolver secrets;
    private final ConnectionTester tester;
    private final String defaultProvider;

    public AccessTools(Resources resources, SecretResolver secrets, ConnectionTester tester, DevopsProperties props) {
        this.resources = resources;
        this.secrets = secrets;
        this.tester = tester;
        this.defaultProvider = props.access().defaultProvider();
    }

    @Tool(name = "devops_list_resources", description = "List the Jenkins servers, registries and Kubernetes clusters this agent "
            + "is configured for, with their names (to pass to other tools), URLs and restrictions. Never includes secrets.")
    public Map<String, Object> list() {
        Systems p = resources.systems();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jenkins", p.jenkins().stream().map(j -> Map.of("name", j.name(), "url", j.url(), "username",
                String.valueOf(j.username()), "readOnly", j.readOnly(), "credentialSyncAllow", j.credentialSyncAllow())).toList());
        out.put("dockerhub", p.dockerhub().stream().map(d -> Map.of("name", d.name(), "namespace", String.valueOf(d.namespace()),
                "username", String.valueOf(d.username()))).toList());
        out.put("kubernetes", p.kubernetes().stream().map(k -> Map.of("name", k.name(), "apiServer", k.apiServer(),
                "allowedNamespaces", k.allowedNamespaces().isEmpty() ? List.of("*") : k.allowedNamespaces(),
                "readOnly", k.readOnly())).toList());
        out.put("secretProviders", secrets.schemes());
        out.put("defaultSecretProvider", defaultProvider);
        return out;
    }

    @Tool(name = "devops_check_access", description = "Test credentials and connectivity to every configured resource "
            + "(or only those of one kind: jenkins, dockerhub, kubernetes). Set refresh=true to drop cached secrets first, "
            + "e.g. after rotating a password on the access server.")
    public List<Map<String, Object>> check(@ToolParam(required = false, description = "jenkins, dockerhub or kubernetes") String kind,
                                           @ToolParam(required = false, description = "Re-fetch secrets from their providers") Boolean refresh) {
        if (Boolean.TRUE.equals(refresh)) {
            secrets.clearCache();
            resources.reset();
        }
        Systems p = resources.systems();
        boolean all = kind == null || kind.isBlank();
        List<Map<String, Object>> out = new ArrayList<>();
        if (all || kind.equals("jenkins")) {
            p.jenkins().forEach(j -> out.add(tester.jenkins(j)));
        }
        if (all || kind.equals("dockerhub")) {
            p.dockerhub().forEach(d -> out.add(tester.dockerhub(d)));
        }
        if (all || kind.equals("kubernetes")) {
            p.kubernetes().forEach(k -> out.add(tester.kubernetes(k)));
        }
        return out;
    }
}
