package dev.arctic.polarion;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Project users and work-item assignee operations. */
public final class Users {

    private static final String USER_FIELDS = "id,name,email,projectRoles";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PolarionClient polarion;
    private final ProjectAllowlistSource allowlist;

    public Users(PolarionClient polarion, ProjectAllowlistSource allowlist) {
        this.polarion = Objects.requireNonNull(polarion, "polarion");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
    }

    /** {@code GET /users} then keep users with a matching project role. Only the requested page is fetched. */
    public List<ProjectUser> listProjectUsers(String projectId, String role, int pageSize, int pageNumber) {
        allowlist.current().check(projectId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("fields[users]", USER_FIELDS);
        query.put("include", "projectRoles");
        query.put("page[size]", String.valueOf(pageSize));
        query.put("page[number]", String.valueOf(pageNumber));
        JsonNode payload = polarion.call("GET", "/users", query, null);
        return filterProjectUsers(payload, projectId, role);
    }

    /**
     * {@code PATCH} work item assignees (replaces the whole list), or preview the body when {@code dryRun} is true.
     * {@code null} dryRun means true. An empty {@code userIds} clears assignees.
     */
    public Object assignWorkItem(String projectId, String workItemId, List<String> userIds, Boolean dryRun) {
        allowlist.current().check(projectId);
        JsonNode body = assignBody(projectId, workItemId, userIds);
        if (dryRun == null || dryRun) {
            return new WorkItemAssignPreview(true, projectId, workItemId, body);
        }
        String path = "/projects/" + encode(projectId) + "/workitems/" + encode(workItemId);
        try {
            polarion.call("PATCH", path, Map.of(), body);
        } catch (PolarionApiException e) {
            if (e.status() == 404) {
                throw new PolarionApiException(404, "Work item not found: " + projectId + "/" + workItemId);
            }
            throw e;
        }
        return new AssignedWorkItem(projectId + "/" + workItemId);
    }

    private static JsonNode assignBody(String projectId, String workItemId, List<String> userIds) {
        ArrayNode assigneeData = JSON.createArrayNode();
        for (String userId : userIds) {
            ObjectNode user = JSON.createObjectNode();
            user.put("type", "users");
            user.put("id", userId);
            assigneeData.add(user);
        }
        ObjectNode assignee = JSON.createObjectNode();
        assignee.set("data", assigneeData);
        ObjectNode relationships = JSON.createObjectNode();
        relationships.set("assignee", assignee);
        ObjectNode data = JSON.createObjectNode();
        data.put("type", "workitems");
        data.put("id", projectId + "/" + workItemId);
        data.set("relationships", relationships);
        ObjectNode root = JSON.createObjectNode();
        root.set("data", data);
        return root;
    }

    private static List<ProjectUser> filterProjectUsers(JsonNode payload, String projectId, String role) {
        List<ProjectUser> matched = new ArrayList<>();
        for (JsonNode item : listData(payload, "user list")) {
            if (userMatchesProjectRole(item, projectId, role)) {
                matched.add(parseProjectUser(item));
            }
        }
        return List.copyOf(matched);
    }

    private static boolean userMatchesProjectRole(JsonNode item, String projectId, String role) {
        JsonNode data = item.path("relationships").path("projectRoles").path("data");
        if (!data.isArray()) {
            return false;
        }
        for (JsonNode entry : data) {
            if (!entry.isObject()) {
                continue;
            }
            String roleId = JsonApi.nonEmptyString(entry.get("id"));
            if (roleId != null && userHasProjectRole(roleId, projectId, role)) {
                return true;
            }
        }
        return false;
    }

    /** Case-sensitive: exact {@code project/role}, or starts with {@code project/} and last segment equals role. */
    static boolean userHasProjectRole(String roleId, String projectId, String role) {
        String expected = projectId + "/" + role;
        if (roleId.equals(expected)) {
            return true;
        }
        String prefix = projectId + "/";
        return roleId.startsWith(prefix) && roleId.substring(roleId.lastIndexOf('/') + 1).equals(role);
    }

    private static ProjectUser parseProjectUser(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new PolarionException("Polarion returned an unexpected user response.");
        }
        String id = JsonApi.nonEmptyString(item.get("id"));
        if (id == null) {
            throw new PolarionException("Polarion returned a user without an id.");
        }
        JsonNode attributes = item.path("attributes");
        return new ProjectUser(
                id,
                JsonApi.nonEmptyString(attributes.get("name")),
                JsonApi.nonEmptyString(attributes.get("email")));
    }

    private static List<JsonNode> listData(JsonNode payload, String kind) {
        JsonNode data = payload == null ? null : payload.get("data");
        if (data == null || !data.isArray()) {
            throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
        }
        return data.valueStream().toList();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A Polarion user visible in a project for a given role filter. */
    public record ProjectUser(String id, String name, String email) {
    }

    /** Result of assigning users when the PATCH succeeds. */
    public record AssignedWorkItem(String id) {
    }

    /** Dry-run preview of an assignee PATCH. */
    public record WorkItemAssignPreview(boolean dryRun, String projectId, String workItemId, JsonNode body) {
    }
}
