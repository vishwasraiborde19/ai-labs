package com.ailabs.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.ailabs.orchestrator.config.OrchestratorProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OrchestratorProperties.class)
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    @Controller
    static class Pages {
        @GetMapping({"/", "/ui", "/ui/"})
        String ui() {
            return "forward:/ui/index.html";
        }
    }
}
