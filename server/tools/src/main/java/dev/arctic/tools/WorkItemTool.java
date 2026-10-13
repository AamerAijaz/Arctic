package dev.arctic.tools;

import java.util.List;

import dev.arctic.polarion.WorkItems;
import org.springframework.ai.tool.annotation.Tool;

/** Work-item tools. They never accept or return a Polarion token. */
public class WorkItemTool {

    private final WorkItems workItems;

    public WorkItemTool(WorkItems workItems) {
        this.workItems = workItems;
    }

    @Tool(name = "list_work_items", description = """
            List work items in a Polarion project.

            Use Lucene `query` to find items, for example `type:requirement` or \
            `type:systemrequirement`. Work-item type ids are project-specific. \
            Polarion requirements are work items, not a separate resource.""")
    public List<WorkItems.WorkItem> listWorkItems(
            String projectId, String query, Integer pageSize, Integer pageNumber) {
        return workItems.listWorkItems(projectId, query, pageSize, pageNumber);
    }

    @Tool(name = "get_work_item", description = """
            Get one work item after create, by project id and work item id.

            Includes `description` and `assignee_ids`. Use this to verify \
            promote_document_requirements. List endpoints do not fetch those fields.""")
    public WorkItems.WorkItem getWorkItem(String projectId, String workItemId) {
        return workItems.getWorkItem(projectId, workItemId);
    }

    @Tool(name = "list_work_item_types", description = """
            List Polarion work-item type ids for a project.

            Call this before create_work_item or promote_document_requirements. \
            Returned `id` is what Polarion expects as `type` (for example \
            hardware, requirement, userneed), not the UI display name.""")
    public List<WorkItems.WorkItemType> listWorkItemTypes(String projectId) {
        return workItems.listWorkItemTypes(projectId);
    }

    @Mutating
    @Tool(name = "create_work_item", description = """
            Create one work item in a Polarion project.

            `type` is the Polarion work-item type id (for example task or defect), \
            not the JSON:API resource name. Call list_work_item_types first to \
            pick a valid id. Call list_projects if project_id is unknown. Default \
            dry_run=true only returns the request Polarion would receive; set \
            dry_run=false to create the item.""")
    public Object createWorkItem(
            String projectId, String type, String title, String description, Boolean dryRun) {
        return workItems.createWorkItem(projectId, type, title, description, null, null, null, dryRun);
    }

    @Mutating
    @Tool(name = "update_work_item", description = """
            Update one work item in a Polarion project.

            Provide at least one of title, description, status, or \
            change_type_to. `change_type_to` is a Polarion work-item type id \
            from list_work_item_types (PATCH query changeTypeTo). Call \
            get_work_item first if you need \
            the current values. Default dry_run=true only returns the PATCH \
            body Polarion would receive; set dry_run=false to apply.""")
    public Object updateWorkItem(
            String projectId,
            String workItemId,
            String title,
            String description,
            String status,
            String changeTypeTo,
            Boolean dryRun) {
        return workItems.updateWorkItem(
                projectId, workItemId, title, description, status, changeTypeTo, dryRun);
    }

    @Mutating
    @Tool(name = "create_work_item_type", description = """
            Add a Polarion work-item type option on a project.

            Polarion stores types as `workitem-type` enumeration options. This \
            GETs the current options, appends `type_id` / `name`, and PATCHes \
            the full list so existing types are not replaced. `type_id` is the \
            id create_work_item expects (for example ux). Default dry_run=true \
            only returns the PATCH Polarion would receive; set dry_run=false \
            to apply. If the id already exists, no PATCH is sent.""")
    public Object createWorkItemType(
            String projectId,
            String typeId,
            String name,
            String description,
            String color,
            String iconUrl,
            Boolean dryRun) {
        return workItems.createWorkItemType(
                projectId, typeId, name, description, color, iconUrl, dryRun);
    }
}
