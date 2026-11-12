package com.ailabs.kube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ailabs.kube.FakeServer.Req;
import com.ailabs.kube.FakeServer.Res;
import com.ailabs.kube.access.EnvSecretProvider;
import com.ailabs.kube.access.PathSecretProvider;
import com.ailabs.kube.access.Redactor;
import com.ailabs.kube.access.SecretResolver;
import com.ailabs.kube.config.KubeProperties;
import com.ailabs.kube.config.KubeProperties.Cluster;
import com.ailabs.kube.k8s.Clusters;
import com.ailabs.kube.tools.ResourceTools;
import com.ailabs.kube.tools.WorkloadTools;

class ToolsTest {

    static final String CORE = """
            {"resources":[
              {"name":"pods","singularName":"pod","namespaced":true,"kind":"Pod","verbs":["get","list"],"shortNames":["po"]},
              {"name":"pods/log","singularName":"","namespaced":true,"kind":"Pod","verbs":["get"]},
              {"name":"secrets","singularName":"secret","namespaced":true,"kind":"Secret","verbs":["get","list"]},
              {"name":"namespaces","singularName":"namespace","namespaced":false,"kind":"Namespace","verbs":["get","list"],"shortNames":["ns"]},
              {"name":"configmaps","singularName":"configmap","namespaced":true,"kind":"ConfigMap","verbs":["get","list","patch"],"shortNames":["cm"]}]}""";
    static final String APIS = """
            {"groups":[{"name":"apps","preferredVersion":{"groupVersion":"apps/v1","version":"v1"}},
                       {"name":"broken.example.com","preferredVersion":{"groupVersion":"broken.example.com/v1","version":"v1"}}]}""";
    static final String APPS = """
            {"resources":[{"name":"deployments","singularName":"deployment","namespaced":true,"kind":"Deployment",
              "verbs":["get","list","patch"],"shortNames":["deploy"]},
              {"name":"deployments/scale","namespaced":true,"kind":"Scale","verbs":["get","patch"]}]}""";

    @TempDir
    Path tmp;
    FakeServer server;
    Map<String, Function<Req, Res>> routes = new HashMap<>();

    @BeforeEach
    void start() throws Exception {
        routes.put("GET /api/v1", r -> Res.json(CORE));
        routes.put("GET /apis", r -> Res.json(APIS));
        routes.put("GET /apis/apps/v1", r -> Res.json(APPS));
        routes.put("GET /apis/broken.example.com/v1", r -> new Res(503, "text/plain", "down", Map.of()));
        server = new FakeServer(routes);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    Clusters clusters(Cluster c) {
        KubeProperties props = new KubeProperties(null, List.of(c), null);
        SecretResolver secrets = new SecretResolver(List.of(new EnvSecretProvider(), new PathSecretProvider()), props, new Redactor());
        return new Clusters(props, secrets);
    }

    Cluster cluster(boolean readOnly, List<String> allowed) throws Exception {
        Path token = Files.writeString(tmp.resolve("t.token"), "sa-token-1234567890\n");
        return new Cluster("lab", server.url(), "path:" + token, null, false, false, readOnly, allowed, null, false);
    }

    @Test
    void listResolvesShortNamesAndSendsSelectorsAndToken() throws Exception {
        routes.put("GET /apis/apps/v1/namespaces/web/deployments", r -> Res.json("""
                {"metadata":{},"items":[{"metadata":{"name":"api","namespace":"web","creationTimestamp":"2026-01-01T00:00:00Z"},
                 "spec":{"replicas":2,"template":{"spec":{"containers":[{"name":"api","image":"api:1"}]}}},
                 "status":{"readyReplicas":2,"updatedReplicas":2,"availableReplicas":2}}]}"""));
        ResourceTools tools = new ResourceTools(clusters(cluster(false, List.of())));

        Map<String, Object> out = tools.list(null, "deploy", "web", "app=api", null, null, null);

        assertThat(out.get("type")).isEqualTo("deployments.apps");
        assertThat(out.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("items")).get(0);
        assertThat(row).containsEntry("name", "api").containsEntry("ready", "2/2");
        Req req = server.requests.stream().filter(r -> r.path().endsWith("/deployments")).findFirst().orElseThrow();
        assertThat(req.query()).contains("labelSelector=app%3Dapi");
        assertThat(req.header("Authorization")).isEqualTo("Bearer sa-token-1234567890");
    }

    @Test
    void secretValuesAreNeverReturned() throws Exception {
        routes.put("GET /api/v1/namespaces/web/secrets/db", r -> Res.json("""
                {"kind":"Secret","apiVersion":"v1","metadata":{"name":"db","namespace":"web",
                 "annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\\"data\\":{\\"password\\":\\"aHVudGVyMg==\\"}}"},
                 "managedFields":[{"manager":"kubectl"}]},
                 "type":"Opaque","data":{"password":"aHVudGVyMg=="}}"""));
        ResourceTools tools = new ResourceTools(clusters(cluster(false, List.of())));

        String yaml = tools.get(null, "secret", "db", "web", null, null);

        assertThat(yaml).doesNotContain("aHVudGVyMg==").doesNotContain("managedFields").doesNotContain("last-applied")
                .contains("password: \"<hidden: 7 bytes>\"");
    }

    @Test
    void applyCreatesWithServerSideApplyAndDefaultsNamespace() throws Exception {
        routes.put("PATCH /api/v1/namespaces/web/configmaps/cfg",
                r -> Res.json("{\"metadata\":{\"name\":\"cfg\",\"resourceVersion\":\"5\"}}"));
        ResourceTools tools = new ResourceTools(clusters(cluster(false, List.of())));

        Map<String, Object> out = tools.apply(null, """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: cfg
                data:
                  a: "1"
                ---
                """, "web", true, null);

        assertThat(out.toString()).contains("configmaps/cfg").contains("created (dry run)");
        Req patch = server.requests.stream().filter(r -> r.method().equals("PATCH")).findFirst().orElseThrow();
        assertThat(patch.header("Content-Type")).isEqualTo("application/apply-patch+yaml");
        assertThat(patch.query()).contains("fieldManager=mcp-kube-agent").contains("dryRun=All");
        assertThat(patch.body()).contains("\"namespace\":\"web\"");
    }

    @Test
    void policyBlocksProtectedNamespacesReadOnlyClustersAndUnconfirmedDeletes() throws Exception {
        ResourceTools open = new ResourceTools(clusters(cluster(false, List.of())));
        assertThatThrownBy(() -> open.apply(null, "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: x, namespace: kube-system}\n",
                null, false, null)).hasMessageContaining("protected");
        assertThatThrownBy(() -> open.apply(null, "apiVersion: v1\nkind: Namespace\nmetadata: {name: team-a}\n",
                null, false, null)).hasMessageContaining("cluster-scoped");
        assertThatThrownBy(() -> open.delete(null, "cm", "x", "web", "y", null, null, null))
                .hasMessageContaining("confirm");

        ResourceTools readOnly = new ResourceTools(clusters(cluster(true, List.of())));
        assertThatThrownBy(() -> readOnly.patch(null, "cm", "x", "web", "{}", null, null, null))
                .hasMessageContaining("read-only");

        ResourceTools scoped = new ResourceTools(clusters(cluster(false, List.of("web"))));
        assertThatThrownBy(() -> scoped.get(null, "pods", "p", "other", null, null))
                .hasMessageContaining("allowed-namespaces");
        assertThat(server.requests).noneMatch(r -> r.method().equals("PATCH") || r.method().equals("DELETE"));
    }

    @Test
    void scaleUsesTheScaleSubresource() throws Exception {
        routes.put("GET /apis/apps/v1/namespaces/web/deployments/api/scale", r -> Res.json("{\"spec\":{\"replicas\":2}}"));
        routes.put("PATCH /apis/apps/v1/namespaces/web/deployments/api/scale", r -> Res.json("{\"spec\":{\"replicas\":5}}"));
        WorkloadTools tools = new WorkloadTools(clusters(cluster(false, List.of())));

        Map<String, Object> out = tools.scale(null, "web", "api", 5, null);

        assertThat(out).containsEntry("from", 2).containsEntry("to", 5);
        Req patch = server.requests.stream().filter(r -> r.method().equals("PATCH")).findFirst().orElseThrow();
        assertThat(patch.body()).isEqualTo("{\"spec\":{\"replicas\":5}}");
    }
}
