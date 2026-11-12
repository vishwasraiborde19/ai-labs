package com.ailabs.kube;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ailabs.kube.http.HttpSupport;
import com.ailabs.kube.k8s.KubeObjects;
import com.fasterxml.jackson.databind.JsonNode;

class KubeObjectsTest {

    @Test
    void podStatusMatchesKubectl() throws Exception {
        JsonNode crash = HttpSupport.JSON.readTree("""
                {"metadata":{"name":"p"},"status":{"phase":"Running","containerStatuses":[
                  {"name":"app","ready":false,"restartCount":7,"state":{"waiting":{"reason":"CrashLoopBackOff"}}}]}}""");
        assertThat(KubeObjects.podStatus(crash)).isEqualTo("CrashLoopBackOff");

        JsonNode init = HttpSupport.JSON.readTree("""
                {"metadata":{"name":"p"},"status":{"phase":"Pending","initContainerStatuses":[
                  {"name":"migrate","state":{"terminated":{"exitCode":1,"reason":"Error"}}}]}}""");
        assertThat(KubeObjects.podStatus(init)).isEqualTo("Init:Error");
    }

    @Test
    void quantities() {
        assertThat(KubeObjects.cpuMilli("250m")).isEqualTo(250);
        assertThat(KubeObjects.cpuMilli("2")).isEqualTo(2000);
        assertThat(KubeObjects.cpuMilli("150000000n")).isEqualTo(150);
        assertThat(KubeObjects.memMi("1Gi")).isEqualTo(1024);
        assertThat(KubeObjects.memMi("2048Ki")).isEqualTo(2);
    }

    @Test
    void workloadSelectorIncludesExpressions() throws Exception {
        JsonNode d = HttpSupport.JSON.readTree("""
                {"metadata":{"name":"d"},"spec":{"selector":{"matchLabels":{"app":"web"},
                  "matchExpressions":[{"key":"tier","operator":"In","values":["a","b"]}]}}}""");
        assertThat(KubeObjects.workloadSelector(d)).isEqualTo("app=web,tier in (a,b)");
    }
}
