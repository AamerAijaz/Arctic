package dev.arctic.tools;

import java.util.List;

import dev.arctic.polarion.Links;
import dev.arctic.polarion.Links.LinkRole;
import dev.arctic.polarion.Links.WorkItemLink;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Work-item link tools. These never accept or return a Polarion token. */
public class LinkTool {

    private final Links links;

    public LinkTool(Links links) {
        this.links = links;
    }

    @Tool(name = "list_link_roles", description = """
            List Polarion work-item link roles for a project.

            Call this before create_work_item_link. Polarion rejects roles that
            violate the project's linkRules (fromTypes / toTypes). Role ids are
            project-specific (for example implements or relates_to).""")
    public List<LinkRole> listLinkRoles(String projectId) {
        return links.listLinkRoles(projectId);
    }

    @Tool(name = "list_work_item_links", description = """
            List outgoing links from a work item to other work items.""")
    public List<WorkItemLink> listWorkItemLinks(String projectId, String workItemId) {
        return links.listWorkItemLinks(projectId, workItemId);
    }

    @Tool(name = "list_work_item_backlinks", description = """
            List incoming links to a work item (what points at this item).

            Use this on a requirement to see which work items implement it.""")
    public List<WorkItemLink> listWorkItemBacklinks(String projectId, String workItemId) {
        return links.listWorkItemBacklinks(projectId, workItemId);
    }

    @Mutating
    @Tool(name = "create_work_item_link", description = """
            Create an outgoing link from a work item to another work item.

            `role` is a Polarion link-role id from list_link_roles, not a display
            name. `target_project_id` defaults to `project_id`. Default
            dry_run=true only returns the POST body Polarion would receive; set
            dry_run=false to create the link.""")
    public Object createWorkItemLink(
            String projectId,
            String workItemId,
            String targetWorkItemId,
            String role,
            @ToolParam(required = false) String targetProjectId,
            @ToolParam(required = false) Boolean dryRun) {
        return links.createWorkItemLink(
                projectId, workItemId, targetWorkItemId, role, targetProjectId, dryRun);
    }

    @Mutating
    @Tool(name = "delete_work_item_link", description = """
            Delete one outgoing link from a work item.

            Default dry_run=true only returns the DELETE path Polarion would
            receive; set dry_run=false to remove the link.""")
    public Object deleteWorkItemLink(
            String projectId,
            String workItemId,
            String targetWorkItemId,
            String role,
            @ToolParam(required = false) String targetProjectId,
            @ToolParam(required = false) Boolean dryRun) {
        return links.deleteWorkItemLink(
                projectId, workItemId, targetWorkItemId, role, targetProjectId, dryRun);
    }
}
