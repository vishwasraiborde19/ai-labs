package com.ailabs.canary.chaos;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "canary.chaos.enabled=true",
        "canary.chaos.token=s3cret",
        "logging.file.name=target/chaos-test/infra-canary.log"})
@AutoConfigureMockMvc
@AutoConfigureObservability
class ChaosControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ChaosService chaos;

    @AfterEach
    void reset() {
        chaos.resetAll();
    }

    @Test
    void changesNeedTheToken() throws Exception {
        mvc.perform(post("/chaos/health").param("status", "DOWN")).andExpect(status().isForbidden());
        mvc.perform(post("/chaos/health").param("status", "DOWN").header("X-Chaos-Token", "wrong")).andExpect(status().isForbidden());
        mvc.perform(get("/chaos/state")).andExpect(status().isOk()).andExpect(jsonPath("$.tokenRequired").value(true));
    }

    @Test
    void healthDownOnlyTouchesOverallHealth() throws Exception {
        mvc.perform(post("/chaos/health").header("X-Chaos-Token", "s3cret").param("status", "DOWN").param("reason", "test"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.faults.health.active").value(true));
        mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable())
           .andExpect(jsonPath("$.components.chaos.status").value("DOWN"))
           .andExpect(jsonPath("$.components.chaos.details.reason").value("test"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void readinessAndLivenessProbesFail() throws Exception {
        mvc.perform(post("/chaos/readiness").header("X-Chaos-Token", "s3cret").param("refuse", "true")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/chaos/liveness").header("X-Chaos-Token", "s3cret").param("broken", "true")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void faultRevertsByItself() throws Exception {
        mvc.perform(post("/chaos/readiness").header("X-Chaos-Token", "s3cret").param("refuse", "true").param("seconds", "1"))
           .andExpect(jsonPath("$.faults.readiness.revertsAt").exists());
        Thread.sleep(1600);
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void injectedErrorsSpareTheConsoleAndActuator() throws Exception {
        mvc.perform(post("/chaos/errors").header("X-Chaos-Token", "s3cret").param("percent", "100").param("status", "503"))
           .andExpect(status().isOk());
        mvc.perform(get("/hello")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("chaos"));
        mvc.perform(get("/chaos/state")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(delete("/chaos/errors").header("X-Chaos-Token", "s3cret")).andExpect(status().isOk());
        mvc.perform(get("/hello")).andExpect(status().isOk());
    }

    @Test
    void memoryIsAllocatedAndReleased() throws Exception {
        mvc.perform(post("/chaos/memory").header("X-Chaos-Token", "s3cret").param("mb", "16"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.heapMb").value(16));
        mvc.perform(delete("/chaos/memory").header("X-Chaos-Token", "s3cret"))
           .andExpect(jsonPath("$.faults.memory.active").value(false));
        mvc.perform(post("/chaos/memory").header("X-Chaos-Token", "s3cret").param("mb", "16000"))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("heap left")));
    }

    @Test
    void diskFillAndDelete() throws Exception {
        mvc.perform(post("/chaos/disk").header("X-Chaos-Token", "s3cret").param("mb", "2"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.writtenMb").value(2));
        mvc.perform(delete("/chaos/disk").header("X-Chaos-Token", "s3cret"))
           .andExpect(jsonPath("$.faults.disk.active").value(false));
    }

    @Test
    void mockValuesAndActiveFaultsAreMetrics() throws Exception {
        mvc.perform(post("/chaos/mock").header("X-Chaos-Token", "s3cret").param("name", "memory_used_percent").param("value", "95"))
           .andExpect(jsonPath("$.mocks.memory_used_percent").value(95.0));
        mvc.perform(post("/chaos/cpu").header("X-Chaos-Token", "s3cret").param("threads", "1").param("seconds", "5"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
           .andExpect(content().string(containsString("canary_mock_value{name=\"memory_used_percent\"} 95.0")))
           .andExpect(content().string(containsString("canary_chaos_active{fault=\"cpu\"} 1.0")));
        mvc.perform(post("/chaos/mock").header("X-Chaos-Token", "s3cret").param("name", "Bad Name!").param("value", "1"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void consolePageIsServed() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString("chaos console")));
    }
}
