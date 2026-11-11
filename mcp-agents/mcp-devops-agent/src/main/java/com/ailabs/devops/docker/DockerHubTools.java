package com.ailabs.devops.docker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ailabs.devops.core.Resources;

/** Skill: Docker Hub image checks. */
@Component
public class DockerHubTools {

    private static final String R = "Registry account name from config; omit for the default (or anonymous if none configured)";

    private final Resources resources;

    public DockerHubTools(Resources resources) {
        this.resources = resources;
    }

    @Tool(name = "dockerhub_image_exists", description = "Check that an image tag was pushed to Docker Hub; returns digest, size, platforms and push time.")
    public Map<String, Object> exists(@ToolParam(required = false, description = R) String registry,
                                      @ToolParam(description = "Repository, e.g. myuser/my-app (or my-app to use the configured namespace)") String repository,
                                      @ToolParam(description = "Tag, e.g. 42-abc1234") String tag) {
        DockerHubClient c = resources.dockerhub(registry);
        Map<String, Object> t = c.findTag(repository, tag);
        if (t == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("repository", c.qualify(repository));
            out.put("tag", tag);
            out.put("exists", false);
            return out;
        }
        t.put("exists", true);
        return t;
    }

    @Tool(name = "dockerhub_list_tags", description = "Most recently pushed tags of a Docker Hub repository.")
    public List<Map<String, Object>> tags(@ToolParam(required = false, description = R) String registry,
                                          @ToolParam(description = "Repository, e.g. myuser/my-app") String repository,
                                          @ToolParam(required = false, description = "How many, default 10, max 100") Integer limit) {
        return resources.dockerhub(registry).tags(repository, limit == null ? 10 : limit);
    }

    @Tool(name = "dockerhub_repository_info", description = "Docker Hub repository metadata: visibility, last update, pull count.")
    public Map<String, Object> info(@ToolParam(required = false, description = R) String registry,
                                    @ToolParam(description = "Repository, e.g. myuser/my-app") String repository) {
        return resources.dockerhub(registry).repository(repository);
    }
}
