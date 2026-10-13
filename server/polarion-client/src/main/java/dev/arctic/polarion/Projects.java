package dev.arctic.polarion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

/** Polarion project list/get operations. */
public final class Projects {

    private static final String PROJECT_FIELDS = "id,name,description,active,trackerPrefix";

    private final PolarionClient client;
    private final ProjectAllowlistSource allowlist;

    public Projects(PolarionClient client, ProjectAllowlistSource allowlist) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
    }

    /** {@code GET /projects} — projects this user can see, filtered by the allowlist after fetch. */
    public List<Project> listProjects(int pageSize, int pageNumber, String query) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fields[projects]", PROJECT_FIELDS);
        params.put("page[size]", String.valueOf(pageSize));
        params.put("page[number]", String.valueOf(pageNumber));
        if (query != null && !query.isEmpty()) {
            params.put("query", query);
        }
        JsonNode payload = client.call("GET", "/projects", params, null);
        List<Project> projects = new ArrayList<>();
        for (JsonNode item : JsonApi.listData(payload, "project list")) {
            projects.add(Project.fromJson(item));
        }
        return allowlist.current().filter(projects, Project::id);
    }

    /** {@code GET /projects/{projectId}}. Allowlist is checked before any HTTP call. */
    public Project getProject(String projectId) {
        allowlist.current().check(projectId);
        String path = "/projects/" + JsonApi.pathSegment(projectId);
        JsonNode payload;
        try {
            payload = client.call("GET", path, Map.of("fields[projects]", PROJECT_FIELDS), null);
        } catch (PolarionApiException e) {
            if (e.status() == 404) {
                throw new PolarionApiException(404, "Project not found: " + projectId);
            }
            throw e;
        }
        return Project.fromJson(JsonApi.singleData(payload, "project"));
    }

    /** One Polarion project. Null fields match the prototype when Polarion omits attributes. */
    public record Project(
            String id, String name, String description, Boolean active, String trackerPrefix) {

        static Project fromJson(JsonNode item) {
            if (item == null || !item.isObject()) {
                throw new PolarionException("Polarion returned an unexpected project response.");
            }
            String id = JsonApi.nonEmptyString(item.get("id"));
            if (id == null) {
                throw new PolarionException("Polarion returned a project without an id.");
            }
            JsonNode attributes = item.path("attributes");
            JsonNode activeNode = attributes.get("active");
            Boolean active = activeNode != null && activeNode.isBoolean() ? activeNode.booleanValue() : null;
            return new Project(
                    id,
                    JsonApi.textValue(attributes.get("name")),
                    JsonApi.textValue(attributes.get("description")),
                    active,
                    JsonApi.textValue(attributes.get("trackerPrefix")));
        }
    }
}
