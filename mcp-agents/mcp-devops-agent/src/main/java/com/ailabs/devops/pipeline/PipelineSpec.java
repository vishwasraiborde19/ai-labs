package com.ailabs.devops.pipeline;

import org.springframework.ai.tool.annotation.ToolParam;

/**
 * What a CI/CD pipeline should do: checkout -> build -> test -> docker build/push -> deploy to
 * Kubernetes -> health check, with rollback on failure. Credential ids refer to credentials stored in
 * Jenkins (see the jenkins_sync_credential tool), never to raw secrets.
 */
public record PipelineSpec(
        @ToolParam(description = "Git repository URL (https or ssh)") String gitUrl,
        @ToolParam(required = false, description = "Branch to build, default main") String gitBranch,
        @ToolParam(required = false, description = "Jenkins credential id for git, if the repo is private") String gitCredentialsId,
        @ToolParam(required = false, description = "maven | gradle | npm | go | none. Default maven") String buildTool,
        @ToolParam(required = false, description = "Shell command that overrides the build tool's default build") String buildCommand,
        @ToolParam(required = false, description = "Shell command that overrides the build tool's default tests") String testCommand,
        @ToolParam(required = false, description = "Run tests, default true") Boolean runTests,
        @ToolParam(description = "Image repository, e.g. myuser/my-app (Docker Hub) or registry.example.com/team/app") String image,
        @ToolParam(required = false, description = "Jenkins username/password credential id for the registry, default dockerhub") String dockerCredentialsId,
        @ToolParam(required = false, description = "Dockerfile path, default Dockerfile") String dockerfile,
        @ToolParam(required = false, description = "Docker build context, default .") String dockerContext,
        @ToolParam(required = false, description = "Also push the 'latest' tag, default false") Boolean pushLatest,
        @ToolParam(required = false, description = "Deploy to Kubernetes, default true") Boolean deploy,
        @ToolParam(required = false, description = "Jenkins secret-file credential id holding a kubeconfig, default kubeconfig") String kubeconfigCredentialsId,
        @ToolParam(required = false, description = "Kubernetes namespace, default default") String namespace,
        @ToolParam(required = false, description = "Deployment name, default derived from the image name") String deployment,
        @ToolParam(required = false, description = "Container in the deployment to update, default same as deployment") String container,
        @ToolParam(required = false, description = "Directory or file of manifests to kubectl apply before setting the image, e.g. k8s/") String manifestsPath,
        @ToolParam(required = false, description = "URL to probe after deploy, e.g. http://my-app.dev.example.com/actuator/health") String healthUrl,
        @ToolParam(required = false, description = "Expected HTTP status, default 200") Integer healthExpectedStatus,
        @ToolParam(required = false, description = "Health check attempts, default 12") Integer healthRetries,
        @ToolParam(required = false, description = "Seconds between attempts, default 10") Integer healthIntervalSeconds,
        @ToolParam(required = false, description = "Run kubectl rollout undo if deploy or health check fails, default true") Boolean rollbackOnFailure,
        @ToolParam(required = false, description = "Jenkins agent label to run on; default any agent") String agentLabel) {
}
