package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionException;
import com.arctic.mcp.polarion.PolarionResources.WorkItem;
import com.arctic.mcp.polarion.PolarionResources.WorkItemPage;
import com.arctic.mcp.polarion.PolarionResources.WorkItemTypeList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Project and work-item tools. These never accept or return a Polarion token. */
public final class WorkItemTools {

    static final String LIST_WORK_ITEMS =
            """
            List work items in a Polarion project.

            Use Lucene `query` to find items, for example `type:requirement` or
            `type:systemrequirement`. Work-item type ids are project-specific.
            Polarion requirements are work items, not a separate resource.\
            """;

    static final String GET_WORK_ITEM =
            """
            Get one work item after create, by project id and work item id.

            Includes `description` and `assignee_ids`. List endpoints do not
            fetch those fields.\
            """;

    static final String CREATE_WORK_ITEM =
            """
            Create one work item in a Polarion project.

            `type` is the Polarion work-item type id (for example task or defect),
            not the JSON:API resource name. Call list_work_item_types first to
            pick a valid id. Call list_projects if project_id is unknown. Default
            dry_run=true only returns the request Polarion would receive; set
            dry_run=false to create the item.\
            """;

    static final String UPDATE_WORK_ITEM =
            """
            Update one work item in a Polarion project.

            Provide at least one of title, description, status, or
            change_type_to. `change_type_to` is a Polarion work-item type id
            from list_work_item_types (PATCH query changeTypeTo). Call
            get_work_item first if you need
            the current values. Default dry_run=true only returns the PATCH
            body Polarion would receive; set dry_run=false to apply.\
            """;

    static final String LIST_WORK_ITEM_TYPES =
            """
            List Polarion work-item type ids for a project.

            Call this before create_work_item. Returned `id` is what Polarion
            expects as `type` (for example hardware, requirement, userneed), not
            the UI display name.\
            """;

    static final String CREATE_WORK_ITEM_TYPE =
            """
            Add a Polarion work-item type option on a project.

            Polarion stores types as `workitem-type` enumeration options. This
            GETs the current options, appends `type_id` / `name`, and PATCHes
            the full list so existing types are not replaced. `type_id` is the
            id create_work_item expects (for example ux). Default dry_run=true
            only returns the PATCH Polarion would receive; set dry_run=false
            to apply. If the id already exists, no PATCH is sent.\
            """;

    private final PolarionClient client;

    public WorkItemTools(PolarionClient client) {
        this.client = client;
    }

    @Tool(name = "list_work_items", description = LIST_WORK_ITEMS)
    public WorkItemPage listWorkItems(
            String project_id,
            @ToolParam(required = false) String query,
            @ToolParam(required = false) Integer page_size,
            @ToolParam(required = false) Integer page_number) {
        int pageNumber = page_number == null ? 1 : page_number;
        try {
            return new WorkItemPage(client.listWorkItems(project_id, query, page_size, pageNumber), pageNumber);
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "get_work_item", description = GET_WORK_ITEM)
    public WorkItem getWorkItem(String project_id, String work_item_id) {
        try {
            return client.getWorkItem(project_id, work_item_id);
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "create_work_item", description = CREATE_WORK_ITEM)
    public Object createWorkItem(
            String project_id,
            String type,
            String title,
            @ToolParam(required = false) String description,
            @ToolParam(required = false) Boolean dry_run) {
        try {
            return client.createWorkItem(project_id, type, title, description, ToolSupport.dryRun(dry_run));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "update_work_item", description = UPDATE_WORK_ITEM)
    public Object updateWorkItem(
            String project_id,
            String work_item_id,
            @ToolParam(required = false) String title,
            @ToolParam(required = false) String description,
            @ToolParam(required = false) String status,
            @ToolParam(required = false) String change_type_to,
            @ToolParam(required = false) Boolean dry_run) {
        try {
            return client.updateWorkItem(
                    project_id, work_item_id, title, description, status, change_type_to, ToolSupport.dryRun(dry_run));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "list_work_item_types", description = LIST_WORK_ITEM_TYPES)
    public WorkItemTypeList listWorkItemTypes(String project_id) {
        try {
            return new WorkItemTypeList(client.listWorkItemTypes(project_id));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }

    @Tool(name = "create_work_item_type", description = CREATE_WORK_ITEM_TYPE)
    public Object createWorkItemType(
            String project_id,
            String type_id,
            String name,
            @ToolParam(required = false) String description,
            @ToolParam(required = false) String color,
            @ToolParam(required = false) String icon_url,
            @ToolParam(required = false) Boolean dry_run) {
        try {
            return client.createWorkItemType(
                    project_id, type_id, name, description, color, icon_url, ToolSupport.dryRun(dry_run));
        } catch (PolarionException exception) {
            throw ToolSupport.failed(exception);
        }
    }
}
