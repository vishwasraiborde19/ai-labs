package com.ailabs.devops.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JenkinsfileGeneratorTest {

    static PipelineSpec spec(String gitUrl, String image, String namespace, String healthUrl, String buildTool) {
        return new PipelineSpec(gitUrl, null, "github", buildTool, null, null, null, image, null, null, null,
                true, null, null, namespace, null, null, "k8s/", healthUrl, null, 5, 3, null, null);
    }

    @Test
    void fullPipelineHasAllStagesInOrder() {
        String j = JenkinsfileGenerator.generate(spec("https://github.com/acme/shop.git", "acme/shop", "dev",
                "http://shop.dev.lab/actuator/health", "maven"));

        assertThat(j).containsSubsequence("stage('Checkout')", "stage('Build')", "stage('Test')",
                "stage('Docker Build & Push')", "stage('Deploy to Kubernetes')", "stage('Health Check')", "post {");
        assertThat(j).contains("IMAGE = 'acme/shop'", "K8S_NAMESPACE = 'dev'", "K8S_DEPLOYMENT = 'shop'",
                "K8S_MANIFESTS = 'k8s/'", "HEALTH_RETRIES = '5'", "credentialsId: 'github'",
                "usernamePassword(credentialsId: 'dockerhub'", "file(credentialsId: 'kubeconfig'",
                "docker push \"$IMAGE:latest\"", "rollout undo", "./mvnw -B -DskipTests package");
        // registry login must stay a shell variable, never Groovy-interpolated
        assertThat(j).contains("--password-stdin $REGISTRY").doesNotContain("${REG_PASS}");
        assertThat(braceBalance(j)).isZero();
    }

    @Test
    void noDeployMeansNoKubernetesOrHealthStages() {
        PipelineSpec s = new PipelineSpec("git@github.com:acme/api.git", "release", null, "npm", null, null, false,
                "registry.lab.local:5000/team/api", "regcred", null, null, null, false, null, null, null, null, null,
                "http://x", null, null, null, null, "docker");
        String j = JenkinsfileGenerator.generate(s);
        assertThat(j).doesNotContain("Deploy to Kubernetes", "Health Check", "stage('Test')", "rollout undo");
        assertThat(j).contains("REGISTRY = 'registry.lab.local:5000'", "agent { label 'docker' }", "npm ci");
        assertThat(braceBalance(j)).isZero();
    }

    @Test
    void rejectsValuesThatCouldInjectGroovyOrShell() {
        assertThatThrownBy(() -> JenkinsfileGenerator.generate(spec("https://x/a.git'; sh 'rm -rf /", "acme/shop", "dev", null, "maven")))
                .hasMessageContaining("gitUrl");
        assertThatThrownBy(() -> JenkinsfileGenerator.generate(spec("https://x/a.git", "Acme/$(id)", "dev", null, "maven")))
                .hasMessageContaining("image");
        assertThatThrownBy(() -> JenkinsfileGenerator.generate(spec("https://x/a.git", "acme/shop", "Dev_NS", null, "maven")))
                .hasMessageContaining("namespace");
        assertThatThrownBy(() -> JenkinsfileGenerator.generate(spec("https://x/a.git", "acme/shop", "dev", "http://h/$X", "maven")))
                .hasMessageContaining("healthUrl");
        assertThatThrownBy(() -> JenkinsfileGenerator.generate(spec("https://x/a.git", "acme/shop", "dev", null, "ant")))
                .hasMessageContaining("buildTool");
    }

    @Test
    void groovyQuotingEscapesQuotesAndBackslashes() {
        assertThat(JenkinsfileGenerator.q("a'b\\c")).isEqualTo("'a\\'b\\\\c'");
    }

    @Test
    void registryDetection() {
        assertThat(JenkinsfileGenerator.registryOf("acme/shop")).isEmpty();
        assertThat(JenkinsfileGenerator.registryOf("ghcr.io/acme/shop")).isEqualTo("ghcr.io");
        assertThat(JenkinsfileGenerator.registryOf("localhost/shop")).isEqualTo("localhost");
    }

    private static int braceBalance(String s) {
        int b = 0;
        for (char c : s.replaceAll("'%\\{http_code}'", "").toCharArray()) {
            if (c == '{') b++;
            if (c == '}') b--;
        }
        // ${...} interpolations are balanced pairs too, so the total must be zero
        return b;
    }
}
