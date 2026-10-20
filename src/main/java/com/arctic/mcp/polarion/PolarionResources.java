package com.arctic.mcp.polarion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Polarion resources returned by Tools. Field names match the Prototype dictionaries. */
public final class PolarionResources {

    private PolarionResources() {}

    public record Project(
            String id,
            String name,
            String description,
            Boolean active,
            @JsonProperty("tracker_prefix") String trackerPrefix) {}

    public record ProjectPage(List<Project> projects, @JsonProperty("page_number") int pageNumber) {}

    public record WorkItem(
            String id,
            String title,
            String type,
            String status,
            String description,
            @JsonProperty("assignee_ids") List<String> assigneeIds) {}

    public record WorkItemPage(
            @JsonProperty("work_items") List<WorkItem> workItems, @JsonProperty("page_number") int pageNumber) {}

    public record CreatedWorkItem(String id, @JsonProperty("portal_url") String portalUrl) {}

    public record WorkItemCreatePreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            Map<String, Object> body) {}

    public record UpdatedWorkItem(String id) {}

    public record WorkItemUpdatePreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("work_item_id") String workItemId,
            Map<String, Object> body,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("change_type_to") String changeTypeTo) {}

    public record WorkItemType(String id, String name, String description) {}

    public record WorkItemTypeList(@JsonProperty("types") List<WorkItemType> types) {}

    public record CreatedWorkItemType(
            String id, String name, String description, @JsonProperty("already_exists") boolean alreadyExists) {}

    public record WorkItemTypeCreatePreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("already_exists") boolean alreadyExists,
            Map<String, Object> body) {}

    public record LinkRole(
            String id,
            String name,
            @JsonProperty("opposite_name") String oppositeName,
            @JsonProperty("link_rules") List<Map<String, Object>> linkRules) {}

    public record LinkRoleList(List<LinkRole> roles) {}

    public record WorkItemLink(
            String id,
            String role,
            @JsonProperty("target_id") String targetId,
            @JsonProperty("target_title") String targetTitle) {}

    public record WorkItemLinkList(List<WorkItemLink> links) {}

    public record CreatedWorkItemLink(String id) {}

    public record WorkItemLinkPreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("work_item_id") String workItemId,
            Map<String, Object> body) {}

    public record DeletedWorkItemLink(String id) {}

    public record WorkItemLinkDeletePreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("work_item_id") String workItemId,
            String role,
            @JsonProperty("target_project_id") String targetProjectId,
            @JsonProperty("target_work_item_id") String targetWorkItemId,
            String path) {}

    public record Document(
            String id,
            @JsonProperty("module_name") String moduleName,
            String title,
            String type,
            String status,
            @JsonProperty("space_id") String spaceId,
            @JsonProperty("portal_url") String portalUrl,
            @JsonProperty("content_summary") String contentSummary) {}

    public record DocumentPage(List<Document> documents, @JsonProperty("page_number") int pageNumber) {}

    public record CreatedDocument(String id, @JsonProperty("portal_url") String portalUrl) {}

    public record DocumentCreatePreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("space_id") String spaceId,
            Map<String, Object> body) {}

    public record DocumentImportPreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("space_id") String spaceId,
            String path,
            Map<String, String> parameters,
            String filename) {}

    public record DocumentPart(
            String id,
            @JsonProperty("part_type") String partType,
            Integer level,
            @JsonProperty("heading_text") String headingText,
            String text,
            @JsonProperty("work_item_id") String workItemId) {}

    public record RequirementWorkItemPreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            String description) {}

    public record DocumentPartPage(List<DocumentPart> parts, @JsonProperty("page_number") int pageNumber) {}

    public record DocumentPartMovePreview(
            @JsonProperty("dry_run") boolean dryRun, String path, Map<String, Object> body) {}

    public record DocumentPartMoveResult(
            String id, boolean moved, String after, String before, String parent) {}

    public record DocumentPartDeletePreview(
            @JsonProperty("dry_run") boolean dryRun, String path, Map<String, Object> body) {}

    public record DocumentPartDeleteResult(List<String> deleted) {}

    public record CreatedDocumentWorkItem(
            String id, @JsonProperty("part_id") String partId, @JsonProperty("portal_url") String portalUrl) {}

    public record DocumentWorkItemCreatePreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("work_item_body") Map<String, Object> workItemBody,
            @JsonProperty("part_body") Map<String, Object> partBody,
            String after,
            String before,
            String parent,
            @JsonProperty("previous_part") String previousPart,
            @JsonProperty("next_part") String nextPart) {}

    public record ProjectUser(String id, String name, String email) {}

    public record ProjectUserPage(List<ProjectUser> users, @JsonProperty("page_number") int pageNumber) {}

    public record AssignedWorkItem(String id) {}

    public record WorkItemAssignPreview(
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("work_item_id") String workItemId,
            Map<String, Object> body) {}
}
