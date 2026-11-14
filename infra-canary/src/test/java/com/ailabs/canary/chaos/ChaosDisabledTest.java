package com.ailabs.canary.chaos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Default settings: chaos is off, state is readable, every change is refused. */
@SpringBootTest
@AutoConfigureMockMvc
class ChaosDisabledTest {
    @Autowired MockMvc mvc;

    @Test
    void changesAreRefusedByDefault() throws Exception {
        mvc.perform(get("/chaos/state")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/chaos/liveness").param("broken", "true")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
