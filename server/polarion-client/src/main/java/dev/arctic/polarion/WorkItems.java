package dev.arctic.polarion;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Polarion work-item and work-item-type operations. */
public final class WorkItems {

    private static final String WORK_ITEM_FIELDS = "id,title,type,status";
    private static final String WORK_ITEM_DETAIL_FIELDS = "id,title,type,status,description,assignee";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PolarionClient polarion;
    private final ProjectAllowlistSource allowlist;

    public WorkItems(PolarionClient polarion, ProjectAllowlistSource allowlist) {
        this.polarion = Objects.requireNonNull(polarion, "polarion");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
    }

    public List<WorkItem> listWorkItems(String projectId, String query, Integer pageSize, Integer pageNumber) {
        allowlist.current().check(projectId);
        int size = pageSize == null ? 100 : pageSize;
        int number = pageNumber == null ? 1 : pageNumber;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fields[workitems]", WORK_ITEM_FIELDS);
        params.put("page[size]", Integer.toString(size));
        params.put("page[number]", Integer.toString(number));
        if (query != null && !query.isEmpty()) {
            params.put("query", query);
        }
        JsonNode payload = polarion.call(
                "GET", "/projects/" + encode(projectId) + "/workitems", params, null);
        return listData(payload, "work item list").stream().map(WorkItems::parseWorkItem).toList();
    }

    public WorkItem getWorkItem(String projectId, String workItemId) {
        allowlist.current().check(projectId);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fields[workitems]", WORK_ITEM_DETAIL_FIELDS);
        params.put("include", "assignee");
        JsonNode payload;
        try {
            payload = polarion.call(
                    "GET",
                    "/projects/" + encode(projectId) + "/workitems/" + encode(workItemId),
                    params,
                    null);
        } catch (PolarionApiException e) {
            throw rewriteWorkItemNotFound(e, projectId, workItemId);
        }
        return parseWorkItem(JsonApi.singleData(payload, "work item"));
    }

    public Object createWorkItem(
            String projectId,
            String wiType,
            String title,
            String description,
            String module,
            String status,
            String severity,
            Boolean dryRun) {
        allowlist.current().check(projectId);
        JsonNode body = workItemCreateBody(wiType, title, description, module, status, severity);
        if (dryRun == null || dryRun) {
            return new WorkItemCreatePreview(true, projectId, body);
        }
        JsonNode payload = polarion.call(
                "POST", "/projects/" + encode(projectId) + "/workitems", Map.of(), body);
        return parseCreatedWorkItem(payload);
    }

    public Object updateWorkItem(
            String projectId,
            String workItemId,
            String title,
            String description,
            String status,
            String changeTypeTo,
            Boolean dryRun) {
        allowlist.current().check(projectId);
        String localId = workItemLocalId(workItemId);
        JsonNode body = workItemUpdateBody(
                projectId, localId, title, description, status, changeTypeTo != null);
        if (dryRun == null || dryRun) {
            return new WorkItemUpdatePreview(true, projectId, localId, body, changeTypeTo);
        }
        Map<String, String> params = Map.of();
        if (changeTypeTo != null && !changeTypeTo.isEmpty()) {
            params = Map.of("changeTypeTo", changeTypeTo);
        }
        try {
            polarion.call(
                    "PATCH",
                    "/projects/" + encode(projectId) + "/workitems/" + encode(localId),
                    params,
                    body);
        } catch (PolarionApiException e) {
            throw rewriteWorkItemNotFound(e, projectId, localId);
        }
        return new UpdatedWorkItem(projectId + "/" + localId);
    }

    public List<WorkItemType> listWorkItemTypes(String projectId) {
        JsonNode payload = getWorkItemTypeEnumeration(projectId);
        List<WorkItemType> types = new ArrayList<>();
        for (JsonNode option : rawEnumerationOptions(payload)) {
            CreatedWorkItemType parsed = workItemTypeFromOption(option, false);
            if (parsed != null) {
                types.add(new WorkItemType(parsed.id(), parsed.name(), parsed.description()));
            }
        }
        return List.copyOf(types);
    }

    public Object createWorkItemType(
            String projectId,
            String typeId,
            String name,
            String description,
            String color,
            String iconUrl,
            Boolean dryRun) {
        allowlist.current().check(projectId);
        typeId = typeId.strip();
        name = name.strip();
        if (typeId.isEmpty() || typeId.chars().anyMatch(Character::isWhitespace)) {
            throw new PolarionException("Work-item type id must be non-empty and contain no spaces.");
        }
        if (name.isEmpty()) {
            throw new PolarionException("Work-item type name must be non-empty.");
        }
        JsonNode payload = getWorkItemTypeEnumeration(projectId);
        List<JsonNode> options = rawEnumerationOptions(payload);
        JsonNode existing = null;
        for (JsonNode option : options) {
            JsonNode id = option.get("id");
            if (id != null && id.isString() && typeId.equals(id.stringValue())) {
                existing = option;
                break;
            }
        }
        boolean preview = dryRun == null || dryRun;
        if (existing != null) {
            CreatedWorkItemType created = workItemTypeFromOption(existing, true);
            if (created == null) {
                throw new PolarionException("Polarion returned a type option without an id.");
            }
            if (preview) {
                return new WorkItemTypeCreatePreview(true, projectId, true, null);
            }
            return created;
        }
        JsonNode newOption = newWorkItemTypeOption(options, typeId, name, description, color, iconUrl);
        ObjectNode body = JSON.createObjectNode();
        ObjectNode data = JSON.createObjectNode();
        data.put("type", "enumerations");
        data.put("id", projectId + "/~/workitem-type/~");
        ObjectNode attributes = JSON.createObjectNode();
        ArrayNode optionsNode = JSON.createArrayNode();
        options.forEach(optionsNode::add);
        optionsNode.add(newOption);
        attributes.set("options", optionsNode);
        data.set("attributes", attributes);
        body.set("data", data);
        if (preview) {
            return new WorkItemTypeCreatePreview(true, projectId, false, body);
        }
        polarion.call(
                "PATCH",
                "/projects/" + encode(projectId) + "/enumerations/~/workitem-type/~",
                Map.of(),
                body);
        String desc = description != null ? description.strip() : "";
        return new CreatedWorkItemType(typeId, name, desc.isEmpty() ? null : desc, false);
    }

    private JsonNode getWorkItemTypeEnumeration(String projectId) {
        allowlist.current().check(projectId);
        return polarion.call(
                "GET",
                "/projects/" + encode(projectId) + "/enumerations/~/workitem-type/~",
                Map.of("fields[enumerations]", "@all"),
                null);
    }

    private static JsonNode workItemCreateBody(
            String wiType,
            String title,
            String description,
            String module,
            String status,
            String severity) {
        ObjectNode attributes = JSON.createObjectNode();
        attributes.put("type", wiType);
        attributes.put("title", title);
        if (description != null && !description.isEmpty()) {
            attributes.set("description", htmlValue(description));
        }
        if (status != null) {
            attributes.put("status", status);
        }
        if (severity != null) {
            attributes.put("severity", severity);
        }
        ObjectNode entry = JSON.createObjectNode();
        entry.put("type", "workitems");
        entry.set("attributes", attributes);
        if (module != null) {
            ObjectNode moduleData = JSON.createObjectNode();
            moduleData.put("type", "documents");
            moduleData.put("id", module);
            ObjectNode moduleRel = JSON.createObjectNode();
            moduleRel.set("data", moduleData);
            ObjectNode relationships = JSON.createObjectNode();
            relationships.set("module", moduleRel);
            entry.set("relationships", relationships);
        }
        ArrayNode data = JSON.createArrayNode();
        data.add(entry);
        ObjectNode body = JSON.createObjectNode();
        body.set("data", data);
        return body;
    }

    private static JsonNode workItemUpdateBody(
            String projectId,
            String workItemId,
            String title,
            String description,
            String status,
            boolean allowEmpty) {
        ObjectNode attributes = JSON.createObjectNode();
        if (title != null) {
            attributes.put("title", title);
        }
        if (description != null) {
            attributes.set("description", htmlValue(description));
        }
        if (status != null) {
            attributes.put("status", status);
        }
        if (attributes.isEmpty() && !allowEmpty) {
            throw new PolarionException(
                    "Provide at least one of title, description, status, or change_type_to.");
        }
        ObjectNode data = JSON.createObjectNode();
        data.put("type", "workitems");
        data.put("id", projectId + "/" + workItemId);
        data.set("attributes", attributes);
        ObjectNode body = JSON.createObjectNode();
        body.set("data", data);
        return body;
    }

    private static ObjectNode htmlValue(String value) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "text/html");
        node.put("value", value);
        return node;
    }

    private static WorkItem parseWorkItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new PolarionException("Polarion returned an unexpected work item response.");
        }
        String id = JsonApi.nonEmptyString(item.get("id"));
        if (id == null) {
            throw new PolarionException("Polarion returned a work item without an id.");
        }
        JsonNode attributes = item.get("attributes");
        if (attributes == null || !attributes.isObject()) {
            attributes = JSON.createObjectNode();
        }
        return new WorkItem(
                id,
                textValue(attributes.get("title")),
                textValue(attributes.get("type")),
                textValue(attributes.get("status")),
                textValue(attributes.get("description")),
                relationshipResourceIds(item, "assignee"));
    }

    private static CreatedWorkItem parseCreatedWorkItem(JsonNode payload) {
        JsonNode data = payload != null && payload.isObject() ? payload.get("data") : null;
        JsonNode item = null;
        if (data != null && data.isArray() && !data.isEmpty()) {
            item = data.get(0);
        } else if (data != null && data.isObject()) {
            item = data;
        }
        if (item == null || !item.isObject()) {
            throw new PolarionException("Polarion returned an unexpected create-work-item response.");
        }
        String id = JsonApi.nonEmptyString(item.get("id"));
        if (id == null) {
            throw new PolarionException("Polarion create response had no work item id.");
        }
        JsonNode links = item.get("links");
        String portal = null;
        if (links != null && links.isObject()) {
            portal = JsonApi.nonEmptyString(links.get("portal"));
        }
        return new CreatedWorkItem(id, portal);
    }

    private static List<JsonNode> rawEnumerationOptions(JsonNode payload) {
        JsonNode data = JsonApi.singleData(payload, "work item type enumeration");
        JsonNode attributes = data.get("attributes");
        if (attributes == null || !attributes.isObject()) {
            attributes = JSON.createObjectNode();
        }
        JsonNode options = attributes.get("options");
        if (options == null || !options.isArray()) {
            throw new PolarionException("Polarion returned an unexpected work-item type response.");
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode option : options) {
            if (option.isObject()) {
                result.add(option);
            }
        }
        return result;
    }

    private static CreatedWorkItemType workItemTypeFromOption(JsonNode option, boolean alreadyExists) {
        String typeId = option.get("id") != null && option.get("id").isString()
                ? option.get("id").stringValue()
                : null;
        if (typeId == null || typeId.isEmpty()) {
            return null;
        }
        JsonNode nameNode = option.get("name");
        JsonNode descriptionNode = option.get("description");
        String name = nameNode != null && nameNode.isString() && !nameNode.stringValue().isEmpty()
                ? nameNode.stringValue()
                : null;
        String description = descriptionNode != null && descriptionNode.isString()
                && !descriptionNode.stringValue().isEmpty()
                ? descriptionNode.stringValue()
                : null;
        return new CreatedWorkItemType(typeId, name, description, alreadyExists);
    }

    private static JsonNode newWorkItemTypeOption(
            List<JsonNode> existing,
            String typeId,
            String name,
            String description,
            String color,
            String iconUrl) {
        JsonNode template = templateWorkItemTypeOption(existing);
        ObjectNode option = JSON.createObjectNode();
        option.put("id", typeId);
        option.put("name", name);
        String desc = description != null ? description.strip() : "";
        if (!desc.isEmpty()) {
            option.put("description", desc);
        }
        String resolvedColor = color != null && !color.isEmpty()
                ? color.strip()
                : stringOrNull(template.get("color"));
        if (resolvedColor != null && !resolvedColor.isEmpty()) {
            option.put("color", resolvedColor);
        }
        String resolvedIcon = iconUrl != null && !iconUrl.isEmpty()
                ? iconUrl.strip()
                : stringOrNull(template.get("iconURL"));
        if (resolvedIcon != null && !resolvedIcon.isEmpty()) {
            option.put("iconURL", resolvedIcon);
        }
        return option;
    }

    private static JsonNode templateWorkItemTypeOption(List<JsonNode> existing) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode option : existing) {
            JsonNode id = option.get("id");
            if (id != null && id.isString()) {
                byId.put(id.stringValue(), option);
            }
        }
        for (String typeId : List.of("softwarerequirement", "systemrequirement", "requirement")) {
            JsonNode option = byId.get(typeId);
            if (option != null) {
                return option;
            }
        }
        return existing.isEmpty() ? JSON.createObjectNode() : existing.get(existing.size() - 1);
    }

    private static String stringOrNull(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static List<JsonNode> listData(JsonNode payload, String kind) {
        JsonNode data = payload != null && payload.isObject() ? payload.get("data") : null;
        if (data == null || !data.isArray()) {
            throw new PolarionException("Polarion returned an unexpected " + kind + " response.");
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        return items;
    }

    private static String textValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isString() && !value.stringValue().isEmpty()) {
            return value.stringValue();
        }
        if (value.isObject()) {
            JsonNode inner = value.get("value");
            if (inner != null && inner.isString() && !inner.stringValue().isEmpty()) {
                return inner.stringValue();
            }
        }
        return null;
    }

    private static List<String> relationshipResourceIds(JsonNode item, String name) {
        JsonNode relationships = item.get("relationships");
        if (relationships == null || !relationships.isObject()) {
            return List.of();
        }
        JsonNode rel = relationships.get(name);
        if (rel == null || !rel.isObject()) {
            return List.of();
        }
        JsonNode data = rel.get("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode entry : data) {
            if (entry != null && entry.isObject()) {
                JsonNode id = entry.get("id");
                if (id != null && id.isString()) {
                    ids.add(id.stringValue());
                }
            }
        }
        return List.copyOf(ids);
    }

    private static String workItemLocalId(String workItemId) {
        int slash = workItemId.lastIndexOf('/');
        return slash < 0 ? workItemId : workItemId.substring(slash + 1);
    }

    private static PolarionApiException rewriteWorkItemNotFound(
            PolarionApiException error, String projectId, String workItemId) {
        if (error.status() == 404) {
            return new PolarionApiException(404, "Work item not found: " + projectId + "/" + workItemId);
        }
        return error;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record WorkItem(String id, String title, String type, String status, String description, List<String> assigneeIds) {}

    public record CreatedWorkItem(String id, String portalUrl) {}

    public record UpdatedWorkItem(String id) {}

    public record WorkItemCreatePreview(boolean dryRun, String projectId, JsonNode body) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkItemUpdatePreview(boolean dryRun, String projectId, String workItemId, JsonNode body, String changeTypeTo) {}

    public record WorkItemType(String id, String name, String description) {}

    public record CreatedWorkItemType(String id, String name, String description, boolean alreadyExists) {}

    public record WorkItemTypeCreatePreview(boolean dryRun, String projectId, boolean alreadyExists, JsonNode body) {}
}
