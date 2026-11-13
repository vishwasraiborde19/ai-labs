package com.ailabs.canary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LogControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void hello() throws Exception {
        mvc.perform(get("/hello")).andExpect(status().isOk()).andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void logCountIsCapped() throws Exception {
        mvc.perform(post("/log").param("level", "warn").param("count", "999999"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.lines").value(10000)).andExpect(jsonPath("$.level").value("WARN"));
    }

    @Test
    void error() throws Exception {
        mvc.perform(post("/error")).andExpect(status().isOk());
    }
}
