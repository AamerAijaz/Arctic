package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionException;
import com.arctic.mcp.polarion.PolarionResources.LinkRoleList;
import com.arctic.mcp.polarion.PolarionResources.WorkItemLinkList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Work-item link tools. These never accept or return a Polarion token. */
public final class LinkTools {

  static final String LIST_LINK_ROLES =
      """
      List Polarion work-item link roles for a project.

      Call this before create_work_item_link. Polarion rejects roles that
      violate the project's linkRules (fromTypes / toTypes). Role ids are
      project-specific (for example implements or relates_to).\
      """;

  static final String LIST_LINKS = "List outgoing links from a work item to other work items.";

  static final String LIST_BACKLINKS =
      """
      List incoming links to a work item (what points at this item).

      Use this on a requirement to see which work items implement it.\
      """;

  static final String CREATE_LINK =
      """
      Create an outgoing link from a work item to another work item.

      `role` is a Polarion link-role id from list_link_roles, not a display
      name. `target_project_id` defaults to `project_id`. Default
      dry_run=true only returns the POST body Polarion would receive; set
      dry_run=false to create the link.\
      """;

  static final String DELETE_LINK =
      """
      Delete one outgoing link from a work item.

      Default dry_run=true only returns the DELETE path Polarion would
      receive; set dry_run=false to remove the link.\
      """;

  private final PolarionClient client;

  public LinkTools(PolarionClient client) {
    this.client = client;
  }

  @Tool(name = "list_link_roles", description = LIST_LINK_ROLES)
  public LinkRoleList listLinkRoles(String project_id) {
    try {
      return new LinkRoleList(client.listLinkRoles(project_id));
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }

  @Tool(name = "list_work_item_links", description = LIST_LINKS)
  public WorkItemLinkList listWorkItemLinks(String project_id, String work_item_id) {
    try {
      return new WorkItemLinkList(client.listWorkItemLinks(project_id, work_item_id));
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }

  @Tool(name = "list_work_item_backlinks", description = LIST_BACKLINKS)
  public WorkItemLinkList listWorkItemBacklinks(String project_id, String work_item_id) {
    try {
      return new WorkItemLinkList(client.listWorkItemBacklinks(project_id, work_item_id));
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }

  @Tool(name = "create_work_item_link", description = CREATE_LINK)
  public Object createWorkItemLink(
      String project_id,
      String work_item_id,
      String target_work_item_id,
      String role,
      @ToolParam(required = false) String target_project_id,
      @ToolParam(required = false) Boolean dry_run) {
    try {
      return client.createWorkItemLink(
          project_id,
          work_item_id,
          target_work_item_id,
          role,
          target_project_id,
          ToolSupport.dryRun(dry_run));
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }

  @Tool(name = "delete_work_item_link", description = DELETE_LINK)
  public Object deleteWorkItemLink(
      String project_id,
      String work_item_id,
      String target_work_item_id,
      String role,
      @ToolParam(required = false) String target_project_id,
      @ToolParam(required = false) Boolean dry_run) {
    try {
      return client.deleteWorkItemLink(
          project_id,
          work_item_id,
          target_work_item_id,
          role,
          target_project_id,
          ToolSupport.dryRun(dry_run));
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }
}
