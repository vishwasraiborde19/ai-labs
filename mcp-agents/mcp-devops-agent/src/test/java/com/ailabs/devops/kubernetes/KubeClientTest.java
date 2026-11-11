package com.ailabs.devops.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ailabs.devops.FakeServer;
import com.ailabs.devops.FakeServer.Res;
import com.ailabs.devops.config.DevopsProperties.KubeCluster;
import com.ailabs.devops.http.HttpSupport;

class KubeClientTest {

    static final String DEPLOY = """
            {"metadata":{"name":"shop","namespace":"dev","uid":"u1","generation":3,
                         "annotations":{"deployment.kubernetes.io/revision":"3"}},
             "spec":{"replicas":2,"selector":{"matchLabels":{"app":"shop"}},
                     "template":{"spec":{"containers":[{"name":"shop","image":"acme/shop:3"}]}}},
             "status":{"observedGeneration":3,"replicas":2,"updatedReplicas":2,"readyReplicas":2,"availableReplicas":2,
                       "conditions":[{"type":"Available","status":"True"}]}}
            """;

    static String rs(int rev, String image, String owner) {
        return """
                {"metadata":{"annotations":{"deployment.kubernetes.io/revision":"%d"},"ownerReferences":[{"uid":"%s"}]},
                 "spec":{"template":{"metadata":{"labels":{"app":"shop","pod-template-hash":"h%d"}},
                                     "spec":{"containers":[{"name":"shop","image":"%s"}]}}}}
                """.formatted(rev, owner, rev, image);
    }

    static KubeClient client(String url) {
        return new KubeClient(new KubeCluster("dev", url, "env:X", null, false, List.of("dev"), false), () -> "tok", null);
    }

    @Test
    void summarizesCompleteRollout() throws Exception {
        var s = KubeClient.summarize(HttpSupport.JSON.readTree(DEPLOY));
        assertThat(s).containsEntry("rollout", "complete").containsEntry("revision", "3");
        assertThat(s.get("images")).isEqualTo(Map.of("shop", "acme/shop:3"));
    }

    @Test
    void rollbackPatchesPreviousOwnedTemplate() throws Exception {
        String list = "{\"items\":[" + rs(3, "acme/shop:3", "u1") + "," + rs(2, "acme/shop:2", "u1") + ","
                + rs(9, "other:9", "someone-else") + "]}";
        try (FakeServer s = new FakeServer(Map.of(
                "GET /apis/apps/v1/namespaces/dev/deployments/shop", r -> Res.json(DEPLOY),
                "GET /apis/apps/v1/namespaces/dev/replicasets", r -> Res.json(list),
                "PATCH /apis/apps/v1/namespaces/dev/deployments/shop", r -> Res.json("{}")))) {
            var out = client(s.url()).rollback("dev", "shop", 0);
            assertThat(out).containsEntry("toRevision", 2).containsEntry("fromRevision", 3);

            var patch = s.requests.stream().filter(r -> r.method().equals("PATCH")).findFirst().orElseThrow();
            assertThat(patch.header("Content-Type")).isEqualTo("application/json-patch+json");
            assertThat(patch.body()).contains("acme/shop:2").doesNotContain("pod-template-hash");
            assertThat(patch.header("Authorization")).isEqualTo("Bearer tok");
            var listReq = s.requests.stream().filter(r -> r.path().endsWith("/replicasets")).findFirst().orElseThrow();
            assertThat(listReq.query()).isEqualTo("labelSelector=app%3Dshop");
        }
    }

    @Test
    void setImagePicksSoleContainer() throws Exception {
        try (FakeServer s = new FakeServer(Map.of(
                "GET /apis/apps/v1/namespaces/dev/deployments/shop", r -> Res.json(DEPLOY),
                "PATCH /apis/apps/v1/namespaces/dev/deployments/shop", r -> Res.json("{}")))) {
            var out = client(s.url()).setImage("dev", "shop", null, "acme/shop:4");
            assertThat(out).containsEntry("container", "shop");
            assertThat(s.requests.get(1).header("Content-Type")).isEqualTo("application/strategic-merge-patch+json");
        }
    }
}
