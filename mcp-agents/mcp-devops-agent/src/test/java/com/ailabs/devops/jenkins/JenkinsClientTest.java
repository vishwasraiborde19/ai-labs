package com.ailabs.devops.jenkins;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ailabs.devops.FakeServer;
import com.ailabs.devops.FakeServer.Res;
import com.ailabs.devops.config.DevopsProperties.JenkinsServer;

class JenkinsClientTest {

    static JenkinsClient client(String url) {
        return new JenkinsClient(new JenkinsServer("main", url, "bot", "env:X", false, false, null, List.of()),
                () -> "api-token", null);
    }

    @Test
    void jobPathHandlesFolders() {
        assertThat(JenkinsClient.jobPath("team/my app")).isEqualTo("/job/team/job/my%20app");
    }

    @Test
    void triggerWithParametersThenWaitForBuildNumber() throws Exception {
        try (FakeServer s = new FakeServer(Map.of(
                "POST /job/shop/buildWithParameters", r -> new Res(201, "text/plain", "",
                        Map.of("Location", "http://jenkins/queue/item/77/")),
                "GET /queue/item/77/api/json", r -> Res.json("{\"executable\":{\"number\":12}}")))) {
            JenkinsClient c = client(s.url());
            long q = c.trigger("shop", Map.of("ENV", "dev"));
            assertThat(q).isEqualTo(77);
            assertThat(c.awaitStart(q, Duration.ofSeconds(5))).isEqualTo(12);

            var post = s.requests.get(0);
            assertThat(post.body()).isEqualTo("ENV=dev");
            assertThat(post.header("Authorization"))
                    .isEqualTo("Basic " + Base64.getEncoder().encodeToString("bot:api-token".getBytes()));
        }
    }

    @Test
    void retriesWithCrumbWhenJenkinsAsksForOne() throws Exception {
        int[] calls = {0};
        try (FakeServer s = new FakeServer(Map.of(
                "POST /job/shop/build", r -> ++calls[0] == 1
                        ? new Res(403, "text/html", "No valid crumb was included in the request", Map.of())
                        : new Res(201, "text/plain", "", Map.of("Location", "http://jenkins/queue/item/5/")),
                "GET /crumbIssuer/api/json", r -> Res.json("{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"abc\"}")))) {
            assertThat(client(s.url()).trigger("shop", Map.of())).isEqualTo(5);
            assertThat(s.requests.get(2).header("Jenkins-Crumb")).isEqualTo("abc");
        }
    }

    @Test
    void upsertCreatesMissingJobInFolder() throws Exception {
        try (FakeServer s = new FakeServer(Map.of(
                "POST /job/team/createItem", r -> new Res(200, "text/plain", "", Map.of())))) {
            assertThat(client(s.url()).upsertJob("team/shop", "<flow-definition/>")).isEqualTo("created");
            var create = s.requests.get(1);
            assertThat(create.query()).isEqualTo("name=shop");
            assertThat(create.header("Content-Type")).startsWith("application/xml");
        }
    }

    @Test
    void xmlEscapesJenkinsfile() {
        String xml = JenkinsXml.inlinePipeline("d", "sh 'a && b' <x>");
        assertThat(xml).startsWith("<?xml").contains("sh &apos;a &amp;&amp; b&apos; &lt;x&gt;");
    }
}
