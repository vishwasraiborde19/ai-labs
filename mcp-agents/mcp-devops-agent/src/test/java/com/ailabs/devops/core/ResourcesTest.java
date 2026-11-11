package com.ailabs.devops.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ailabs.devops.access.Redactor;
import com.ailabs.devops.access.SecretResolver;
import com.ailabs.devops.config.ConfigStore;
import com.ailabs.devops.config.DevopsProperties;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.config.DevopsProperties.KubeCluster;
import com.ailabs.devops.config.Systems;

class ResourcesTest {

    @TempDir
    Path dir;

    Resources resources(List<JenkinsServer> jenkins, List<KubeCluster> kube) {
        var props = new DevopsProperties(null, null, null, null, null, null, null, null);
        var store = new ConfigStore(dir.resolve("systems.yml"), new Systems(jenkins, null, kube, null));
        return new Resources(store, new SecretResolver(List.of(), props, new Redactor()));
    }

    static JenkinsServer jenkins(String name, List<String> allow) {
        return new JenkinsServer(name, "http://j", "u", "env:T", false, false, null, allow);
    }

    @Test
    void defaultsToFirstAndRejectsUnknownNames() {
        Resources r = resources(List.of(jenkins("main", null), jenkins("legacy", null)), List.of());
        assertThat(r.jenkinsConfig(null).name()).isEqualTo("main");
        assertThat(r.jenkinsConfig("legacy").name()).isEqualTo("legacy");
        assertThatThrownBy(() -> r.jenkinsConfig("nope")).hasMessageContaining("Configured: main, legacy");
    }

    @Test
    void namespaceAllowListAndReadOnlyAreEnforced() {
        Resources r = resources(List.of(), List.of(
                new KubeCluster("prod", "https://k", "env:T", null, false, List.of("prod"), true)));
        assertThat(r.requireNamespace("prod", "prod", false).name()).isEqualTo("prod");
        assertThatThrownBy(() -> r.requireNamespace("prod", "kube-system", false)).hasMessageContaining("not in allowed-namespaces");
        assertThatThrownBy(() -> r.requireNamespace("prod", "prod", true)).hasMessageContaining("read-only");
    }

    @Test
    void credentialSyncAllowList() {
        JenkinsServer j = jenkins("main", List.of("access:dockerhub/*", "access:k8s/dev/kubeconfig"));
        assertThat(j.maySync("access:dockerhub/ci/token")).isTrue();
        assertThat(j.maySync("access:k8s/dev/kubeconfig")).isTrue();
        assertThat(j.maySync("access:k8s/prod/kubeconfig")).isFalse();
        assertThat(jenkins("other", null).maySync("access:dockerhub/x")).isFalse();
    }
}
