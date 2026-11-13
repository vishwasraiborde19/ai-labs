package com.ailabs.kube;

import java.util.Arrays;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.ailabs.kube.access.Redactor;
import com.ailabs.kube.config.KubeProperties;
import com.ailabs.kube.core.GuardedToolCallback;
import com.ailabs.kube.tools.ClusterTools;
import com.ailabs.kube.tools.DiagnoseTools;
import com.ailabs.kube.tools.NodeTools;
import com.ailabs.kube.tools.ResourceTools;
import com.ailabs.kube.tools.WorkloadTools;

@SpringBootApplication
@EnableConfigurationProperties(KubeProperties.class)
public class KubeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubeAgentApplication.class, args);
    }

    /** Every @Tool method, each wrapped so calls are audited and results are redacted. */
    @Bean
    ToolCallbackProvider kubeTools(ClusterTools cluster, ResourceTools resources, WorkloadTools workloads,
                                   DiagnoseTools diagnose, NodeTools nodes, Redactor redactor) {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(cluster, resources, workloads, diagnose, nodes)
                .build()
                .getToolCallbacks();
        ToolCallback[] guarded = Arrays.stream(raw)
                .map(cb -> (ToolCallback) new GuardedToolCallback(cb, redactor))
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(guarded);
    }
}
