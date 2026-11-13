package com.ailabs.commandcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Command Center: one entry point to every system, app and agent in the lab. A catalog of systems
 * with their links, how to log in, health, usage guides and how they fit together.
 * UI at http://127.0.0.1:8780/ui
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CommandCenterApplication {

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        SpringApplication.run(CommandCenterApplication.class, args);
    }

    @Controller
    static class Pages {
        @GetMapping({"/", "/ui", "/ui/"})
        String index() {
            return "forward:/ui/index.html";
        }
    }
}
