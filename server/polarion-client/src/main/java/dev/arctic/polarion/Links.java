package dev.arctic.polarion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Polarion work-item link roles, links, backlinks, create, and delete. */
public final class Links {

    private static final String WORK_ITEM_FIELDS = "id,title,type,status";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PolarionClient client;
    private final ProjectAllowlistSource allowlist;

    public Links(PolarionClient client, ProjectAllowlistSource allowlist) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
    }

    /** {@code GET /projects/{projectId}/enumerations/~/workitem-link-role/~}. */
    public List<LinkRole> listLinkRoles(String projectId) {
        allowlist.current().check(projectId);
        String path = "/projects/" + JsonApi.pathSegment(projectId) + "/enumerations/~/workitem-link-role/~";
        JsonNode payload = client.call("GET", path, Map.of(), null);
        return parseLinkRoles(payload);
    }

    /** {@code GET .../linkedworkitems}. */
    public List<WorkItemLink> listWorkItemLinks(String projectId, String workItemId) {
        return listWorkItemLinks(projectId, workItemId, false);
    }

    /** {@code GET .../backlinkedworkitems}. */
    public List<WorkItemLink> listWorkItemBacklinks(String projectId, String workItemId) {
        return listWorkItemLinks(projectId, workItemId, true);
    }

    /**
     * {@code POST .../linkedworkitems}, or the would-be body when {@code dryRun} is true.
     * Null {@code dryRun} means true. Source and target projects are both allowlist-checked before HTTP.
     */
    public Object createWorkItemLink(
            String projectId,
            String workItemId,
            String targetWorkItemId,
            String role,
            String targetProjectId,
            Boolean dryRun) {
        allowlist.current().check(projectId);
        String targetProject = targetProjectId != null ? targetProjectId : projectId;
        allowlist.current().check(targetProject);
        JsonNode body = linkCreateBody(targetProject, targetWorkItemId, role);
        if (dryRun == null || dryRun) {
            return new WorkItemLinkPreview(true, projectId, workItemId, body);
        }
        String path = "/projects/" + JsonApi.pathSegment(projectId)
                + "/workitems/" + JsonApi.pathSegment(workItemId)
                + "/linkedworkitems";
        JsonNode payload;
        try {
            payload = client.call("POST", path, Map.of(), body);
        } catch (PolarionApiException e) {
            if (e.status() == 404) {
                throw new PolarionApiException(404, "Work item not found: " + projectId + "/" + workItemId);
            }
            throw e;
        }
        return CreatedWorkItemLink.fromJson(payload);
    }

    /**
     * {@code DELETE} one linkedworkitems resource, or a path preview when {@code dryRun} is true.
     * Null {@code dryRun} means true. Source and target projects are both allowlist-checked before HTTP.
     */
    public Object deleteWorkItemLink(
            String projectId,
            String workItemId,
            String targetWorkItemId,
            String role,
            String targetProjectId,
            Boolean dryRun) {
        allowlist.current().check(projectId);
        String targetProject = targetProjectId != null ? targetProjectId : projectId;
        allowlist.current().check(targetProject);
        String path = "/projects/" + JsonApi.pathSegment(projectId)
                + "/workitems/" + JsonApi.pathSegment(workItemId)
                + "/linkedworkitems/" + JsonApi.pathSegment(role)
                + "/" + JsonApi.pathSegment(targetProject)
                + "/" + JsonApi.pathSegment(targetWorkItemId);
        String linkId = projectId + "/" + workItemId + "/" + role + "/" + targetProject + "/" + targetWorkItemId;
        if (dryRun == null || dryRun) {
            return new WorkItemLinkDeletePreview(
                    true, projectId, workItemId, role, targetProject, targetWorkItemId, path);
        }
        try {
            client.call("DELETE", path, Map.of(), null);
        } catch (PolarionApiException e) {
            if (e.status() == 404) {
                throw new PolarionApiException(404, "Link not found: " + linkId);
            }
            throw e;
        }
        return new DeletedWorkItemLink(linkId);
    }

    private List<WorkItemLink> listWorkItemLinks(String projectId, String workItemId, boolean backlinks) {
        allowlist.current().check(projectId);
        String collection = backlinks ? "backlinkedworkitems" : "linkedworkitems";
        String kind = backlinks ? "backlink list" : "link list";
        String path = "/projects/" + JsonApi.pathSegment(projectId)
                + "/workitems/" + JsonApi.pathSegment(workItemId)
                + "/" + collection;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fields[linkedworkitems]", "role");
        params.put("fields[workitems]", WORK_ITEM_FIELDS);
        params.put("include", "workItem");
        JsonNode payload;
        try {
            payload = client.call("GET", path, params, null);
        } catch (PolarionApiException e) {
            if (e.status() == 404) {
                throw new PolarionApiException(404, "Work item not found: " + projectId + "/" + workItemId);
            }
            throw e;
        }
        Map<String, String> includedTitles = includedTitles(payload);
        List<WorkItemLink> links = new ArrayList<>();
        for (JsonNode entry : JsonApi.listData(payload, kind)) {
            links.add(WorkItemLink.fromJson(entry, includedTitles));
        }
        return links;
    }

    private static List<LinkRole> parseLinkRoles(JsonNode payload) {
        JsonNode data = JsonApi.singleData(payload, "link role enumeration");
        JsonNode attributes = data.path("attributes");
        JsonNode options = attributes.get("options");
        if (options == null || !options.isArray()) {
            throw new PolarionException("Polarion returned an unexpected link-role response.");
        }
        List<LinkRole> roles = new ArrayList<>();
        for (JsonNode option : options) {
            if (option == null || !option.isObject()) {
                continue;
            }
            String id = JsonApi.nonEmptyString(option.get("id"));
            if (id == null) {
                continue;
            }
            String name = JsonApi.nonEmptyString(option.get("name"));
            String opposite = JsonApi.nonEmptyString(option.get("oppositeName"));
            JsonNode rawRules = option.get("linkRules");
            JsonNode rules = rawRules != null && rawRules.isArray() ? rawRules : null;
            roles.add(new LinkRole(id, name, opposite, rules));
        }
        return roles;
    }

    private static JsonNode linkCreateBody(String targetProjectId, String targetWorkItemId, String role) {
        ObjectNode relationshipData = JSON.createObjectNode()
                .put("type", "workitems")
                .put("id", targetProjectId + "/" + targetWorkItemId);
        ObjectNode workItem = JSON.createObjectNode();
        workItem.set("data", relationshipData);
        ObjectNode relationships = JSON.createObjectNode();
        relationships.set("workItem", workItem);
        ObjectNode entry = JSON.createObjectNode();
        entry.put("type", "linkedworkitems");
        entry.set("attributes", JSON.createObjectNode().put("role", role));
        entry.set("relationships", relationships);
        ArrayNode data = JSON.createArrayNode().add(entry);
        ObjectNode body = JSON.createObjectNode();
        body.set("data", data);
        return body;
    }

    private static Map<String, String> includedTitles(JsonNode payload) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode included = payload == null ? null : payload.get("included");
        if (included == null || !included.isArray()) {
            return result;
        }
        for (JsonNode item : included) {
            if (item == null || !item.isObject() || !"workitems".equals(item.path("type").asString())) {
                continue;
            }
            String id = JsonApi.nonEmptyString(item.get("id"));
            if (id == null) {
                continue;
            }
            result.put(id, JsonApi.textValue(item.path("attributes").get("title")));
        }
        return result;
    }

    public record LinkRole(String id, String name, String oppositeName, JsonNode linkRules) {
    }

    public record WorkItemLink(String id, String role, String targetId, String targetTitle) {

        static WorkItemLink fromJson(JsonNode item, Map<String, String> includedTitles) {
            if (item == null || !item.isObject()) {
                throw new PolarionException("Polarion returned an unexpected work item link.");
            }
            String id = JsonApi.nonEmptyString(item.get("id"));
            if (id == null) {
                throw new PolarionException("Polarion returned a work item link without an id.");
            }
            String role = JsonApi.textValue(item.path("attributes").get("role"));
            String targetId = relationshipWorkItemId(item);
            String targetTitle = targetId != null ? includedTitles.get(targetId) : null;
            return new WorkItemLink(id, role, targetId, targetTitle);
        }

        private static String relationshipWorkItemId(JsonNode item) {
            JsonNode data = item.path("relationships").path("workItem").path("data");
            return data.isObject() ? JsonApi.nonEmptyString(data.get("id")) : null;
        }
    }

    public record CreatedWorkItemLink(String id) {

        static CreatedWorkItemLink fromJson(JsonNode payload) {
            JsonNode data = payload == null ? null : payload.get("data");
            JsonNode item = null;
            if (data != null && data.isArray() && !data.isEmpty()) {
                item = data.get(0);
            } else if (data != null && data.isObject()) {
                item = data;
            }
            if (item == null || !item.isObject()) {
                throw new PolarionException("Polarion returned an unexpected create-link response.");
            }
            String id = JsonApi.nonEmptyString(item.get("id"));
            if (id == null) {
                throw new PolarionException("Polarion create-link response had no id.");
            }
            return new CreatedWorkItemLink(id);
        }
    }

    public record WorkItemLinkPreview(boolean dryRun, String projectId, String workItemId, JsonNode body) {
    }

    public record DeletedWorkItemLink(String id) {
    }

    public record WorkItemLinkDeletePreview(
            boolean dryRun,
            String projectId,
            String workItemId,
            String role,
            String targetProjectId,
            String targetWorkItemId,
            String path) {
    }
}
