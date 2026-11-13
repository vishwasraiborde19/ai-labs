package com.ailabs.canary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InfraCanaryApplication {
    public static void main(String[] args) {
        SpringApplication.run(InfraCanaryApplication.class, args);
    }
}
