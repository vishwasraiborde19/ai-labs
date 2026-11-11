package com.ailabs.devops.kubernetes;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.devops.core.Resources;

/** Skill: deploy to and inspect Kubernetes clusters. */
@Component
public class KubernetesTools {

    private static final String C = "Cluster name from config; omit for the default";

    private final Resources resources;

    public KubernetesTools(Resources resources) {
        this.resources = resources;
    }

    @Tool(name = "k8s_list_deployments", description = "List deployments in a namespace with replicas, images and rollout state.")
    public List<Map<String, Object>> list(@ToolParam(required = false, description = C) String cluster,
                                          @ToolParam(description = "Namespace") String namespace) {
        resources.requireNamespace(cluster, namespace, false);
        return resources.kube(cluster).listDeployments(namespace);
    }

    @Tool(name = "k8s_deployment_status", description = "Rollout state of a deployment (complete, progressing, failed). "
            + "With waitSeconds, waits for the rollout to finish, like kubectl rollout status.")
    public Map<String, Object> status(@ToolParam(required = false, description = C) String cluster,
                                      @ToolParam(description = "Namespace") String namespace,
                                      @ToolParam(description = "Deployment name") String deployment,
                                      @ToolParam(required = false, description = "Seconds to wait for completion, default 0") Integer waitSeconds) {
        resources.requireNamespace(cluster, namespace, false);
        KubeClient k = resources.kube(cluster);
        int wait = waitSeconds == null ? 0 : Math.max(0, Math.min(waitSeconds, 900));
        return wait == 0 ? k.deploymentStatus(namespace, deployment) : k.awaitRollout(namespace, deployment, Duration.ofSeconds(wait));
    }

    @Tool(name = "k8s_pods", description = "List pods of a deployment (or matching a label selector) with readiness, restarts and waiting reasons such as CrashLoopBackOff.")
    public List<Map<String, Object>> pods(@ToolParam(required = false, description = C) String cluster,
                                          @ToolParam(description = "Namespace") String namespace,
                                          @ToolParam(required = false, description = "Deployment name") String deployment,
                                          @ToolParam(required = false, description = "Label selector, e.g. app=my-app, used when deployment is omitted") String labelSelector) {
        resources.requireNamespace(cluster, namespace, false);
        KubeClient k = resources.kube(cluster);
        return deployment != null && !deployment.isBlank() ? k.deploymentPods(namespace, deployment) : k.pods(namespace, labelSelector);
    }

    @Tool(name = "k8s_pod_logs", description = "Last lines of a pod's log. Set previous=true to read the crashed container's previous run.")
    public String logs(@ToolParam(required = false, description = C) String cluster,
                       @ToolParam(description = "Namespace") String namespace,
                       @ToolParam(description = "Pod name") String pod,
                       @ToolParam(required = false, description = "Container name if the pod has several") String container,
                       @ToolParam(required = false, description = "Lines from the end, default 200") Integer tailLines,
                       @ToolParam(required = false, description = "Logs of the previous container instance") Boolean previous) {
        resources.requireNamespace(cluster, namespace, false);
        return resources.kube(cluster).logs(namespace, pod, container,
                tailLines == null ? 200 : Math.min(tailLines, 5000), Boolean.TRUE.equals(previous));
    }

    @Tool(name = "k8s_events", description = "Recent events in a namespace, optionally for one object (deployment, replicaset or pod name). Newest first.")
    public List<Map<String, Object>> events(@ToolParam(required = false, description = C) String cluster,
                                            @ToolParam(description = "Namespace") String namespace,
                                            @ToolParam(required = false, description = "Object name to filter on") String objectName,
                                            @ToolParam(required = false, description = "Max events, default 30") Integer limit) {
        resources.requireNamespace(cluster, namespace, false);
        return resources.kube(cluster).events(namespace, objectName, limit == null ? 30 : Math.min(limit, 200));
    }

    @Tool(name = "k8s_set_image", description = "Deploy an image to a deployment by updating one container's image "
            + "(kubectl set image), then optionally wait for the rollout.")
    public Map<String, Object> setImage(@ToolParam(required = false, description = C) String cluster,
                                        @ToolParam(description = "Namespace") String namespace,
                                        @ToolParam(description = "Deployment name") String deployment,
                                        @ToolParam(required = false, description = "Container name; optional if the deployment has one container") String container,
                                        @ToolParam(description = "Full image reference, e.g. myuser/my-app:42-abc1234") String image,
                                        @ToolParam(required = false, description = "Seconds to wait for the rollout, default 180") Integer waitSeconds) {
        resources.requireNamespace(cluster, namespace, true);
        KubeClient k = resources.kube(cluster);
        Map<String, Object> out = new LinkedHashMap<>(k.setImage(namespace, deployment, container, image));
        int wait = waitSeconds == null ? 180 : Math.max(0, Math.min(waitSeconds, 900));
        if (wait > 0) {
            out.put("status", k.awaitRollout(namespace, deployment, Duration.ofSeconds(wait)));
        }
        return out;
    }

    @Tool(name = "k8s_restart", description = "Rolling restart of a deployment (kubectl rollout restart).")
    public Map<String, Object> restart(@ToolParam(required = false, description = C) String cluster,
                                       @ToolParam(description = "Namespace") String namespace,
                                       @ToolParam(description = "Deployment name") String deployment) {
        resources.requireNamespace(cluster, namespace, true);
        resources.kube(cluster).restart(namespace, deployment);
        return Map.of("deployment", deployment, "state", "restart triggered");
    }

    @Tool(name = "k8s_scale", description = "Set the replica count of a deployment.")
    public Map<String, Object> scale(@ToolParam(required = false, description = C) String cluster,
                                     @ToolParam(description = "Namespace") String namespace,
                                     @ToolParam(description = "Deployment name") String deployment,
                                     @ToolParam(description = "Replicas, 0..50") Integer replicas) {
        if (replicas == null || replicas < 0 || replicas > 50) {
            throw new IllegalArgumentException("replicas must be 0..50");
        }
        resources.requireNamespace(cluster, namespace, true);
        resources.kube(cluster).scale(namespace, deployment, replicas);
        return Map.of("deployment", deployment, "replicas", replicas);
    }

    @Tool(name = "k8s_rollback", description = "Roll a deployment back to the previous revision, or to toRevision (kubectl rollout undo).")
    public Map<String, Object> rollback(@ToolParam(required = false, description = C) String cluster,
                                        @ToolParam(description = "Namespace") String namespace,
                                        @ToolParam(description = "Deployment name") String deployment,
                                        @ToolParam(required = false, description = "Revision to return to; omit for the previous one") Integer toRevision) {
        resources.requireNamespace(cluster, namespace, true);
        return resources.kube(cluster).rollback(namespace, deployment, toRevision == null ? 0 : toRevision);
    }
}
