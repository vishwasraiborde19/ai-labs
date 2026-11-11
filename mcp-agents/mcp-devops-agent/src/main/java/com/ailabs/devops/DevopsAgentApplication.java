package com.ailabs.devops;

import java.util.Arrays;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.ailabs.devops.access.Redactor;
import com.ailabs.devops.admin.AdminPassword;
import com.ailabs.devops.config.DevopsProperties;
import com.ailabs.devops.core.AccessTools;
import com.ailabs.devops.core.GuardedToolCallback;
import com.ailabs.devops.docker.DockerHubTools;
import com.ailabs.devops.health.HealthTools;
import com.ailabs.devops.jenkins.JenkinsTools;
import com.ailabs.devops.kubernetes.KubernetesTools;
import com.ailabs.devops.pipeline.PipelineTools;

@SpringBootApplication
@EnableConfigurationProperties(DevopsProperties.class)
public class DevopsAgentApplication {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("admin-password")) {
            AdminPassword.cli();
            return;
        }
        SpringApplication.run(DevopsAgentApplication.class, args);
    }

    /** Every @Tool method, each wrapped so calls are audited and results are redacted. */
    @Bean
    ToolCallbackProvider devopsTools(AccessTools access, JenkinsTools jenkins, PipelineTools pipeline,
                                     DockerHubTools docker, KubernetesTools kubernetes, HealthTools health,
                                     Redactor redactor) {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(access, jenkins, pipeline, docker, kubernetes, health)
                .build()
                .getToolCallbacks();
        ToolCallback[] guarded = Arrays.stream(raw)
                .map(cb -> (ToolCallback) new GuardedToolCallback(cb, redactor))
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(guarded);
    }
}
