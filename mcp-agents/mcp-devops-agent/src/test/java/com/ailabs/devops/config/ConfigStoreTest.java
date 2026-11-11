package com.ailabs.devops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ailabs.devops.config.DevopsProperties.DockerRegistry;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;
import com.ailabs.devops.config.DevopsProperties.KubeCluster;

class ConfigStoreTest {

    @TempDir
    Path dir;

    static Systems sample() {
        return new Systems(
                List.of(new JenkinsServer("main", "http://jenkins:8080", "bot", "access:jenkins/main/api-token",
                        false, false, null, List.of("access:dockerhub/*"))),
                List.of(new DockerRegistry("default", "acme", "acme", "access:dockerhub/ci/token")),
                List.of(new KubeCluster("dev", "https://10.0.0.1:6443", "access:k8s/dev/token", "access:k8s/dev/ca",
                        false, List.of("dev"), false)),
                new DevopsProperties.Health(List.of("*.lab.local")));
    }

    @Test
    void seedsFileOnceAndWritesKebabCaseYaml() throws Exception {
        Path f = dir.resolve("systems.yml");
        ConfigStore store = new ConfigStore(f, sample());
        String yaml = Files.readString(f);
        assertThat(yaml).contains("token-ref: access:jenkins/main/api-token", "api-server:", "allowed-namespaces:",
                "credential-sync-allow:", "allowed-hosts:").doesNotContain("tokenRef");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(f))).isEqualTo("rw-------");
        assertThat(store.current()).isEqualTo(sample());

        // a second start must not overwrite the file with the (now different) seed
        ConfigStore again = new ConfigStore(f, Systems.empty());
        assertThat(again.current()).isEqualTo(sample());
    }

    @Test
    void saveRequiresCurrentVersion() {
        ConfigStore store = new ConfigStore(dir.resolve("systems.yml"), null);
        assertThat(store.snapshot().version()).isEqualTo("none");

        var saved = store.save(sample(), "none", "test");
        assertThat(saved.version()).hasSize(64);
        assertThatThrownBy(() -> store.save(Systems.empty(), "none", "test")).isInstanceOf(ConfigStore.ConflictException.class);
        assertThat(store.save(Systems.empty(), saved.version(), "test").systems().jenkins()).isEmpty();
        assertThat(dir.resolve("systems.yml.bak")).exists();
    }

    @Test
    void rejectsInvalidSystems() {
        ConfigStore store = new ConfigStore(dir.resolve("systems.yml"), null);
        Systems bad = new Systems(List.of(
                new JenkinsServer("main", "ftp://x", "bot", "env:T", false, false, null, null),
                new JenkinsServer("main", null, "bot", "env:T", false, false, null, null)),
                null, List.of(new KubeCluster("dev", "https://k", "env:T", null, false, List.of("Bad_NS"), false)), null);
        assertThatThrownBy(() -> store.save(bad, "none", "test"))
                .isInstanceOfSatisfying(ConfigStore.InvalidConfigException.class, e -> assertThat(e.errors()).containsExactlyInAnyOrder(
                        "jenkins 'main': URL must be http(s)://host[:port]",
                        "jenkins 'main' is defined twice",
                        "jenkins 'main' is missing url",
                        "kubernetes 'dev': 'Bad_NS' is not a valid namespace"));
    }

    @Test
    void picksUpExternalEditsAndIgnoresBrokenOnes() throws Exception {
        Path f = dir.resolve("systems.yml");
        ConfigStore store = new ConfigStore(f, sample());
        long gen = store.generation();

        Files.writeString(f, """
                jenkins:
                  - name: other
                    url: http://other:8080
                    username: u
                    token-ref: env:T
                """);
        Thread.sleep(1100);
        assertThat(store.current().jenkins()).extracting(JenkinsServer::name).containsExactly("other");
        assertThat(store.generation()).isGreaterThan(gen);

        Files.writeString(f, "jenkins:\n  - name: x\n    unknown-field: 1\n");
        Thread.sleep(1100);
        assertThat(store.current().jenkins()).extracting(JenkinsServer::name).containsExactly("other");
    }
}
