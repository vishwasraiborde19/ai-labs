package com.ailabs.canary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "logging.file.name=target/canary-test/infra-canary.log",
        "canary.dns-name=localhost",
        "canary.http-targets=bad=http://127.0.0.1:1/nothing"})
@AutoConfigureMockMvc
class CanaryControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void failingTargetGives503WithEveryCheckListed() throws Exception {
        mvc.perform(get("/canary"))
           .andExpect(status().isServiceUnavailable())
           .andExpect(jsonPath("$.status").value("FAIL"))
           .andExpect(jsonPath("$.checks[0].name").value("log-volume"))
           .andExpect(jsonPath("$.checks[0].ok").value(true))
           .andExpect(jsonPath("$.checks[1].name").value("dns"))
           .andExpect(jsonPath("$.checks[1].ok").value(true))
           .andExpect(jsonPath("$.checks[2].name").value("http:bad"))
           .andExpect(jsonPath("$.checks[2].ok").value(false));
    }
}
