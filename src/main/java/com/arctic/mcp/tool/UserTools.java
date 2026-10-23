package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionException;
import com.arctic.mcp.polarion.PolarionResources.ProjectUserPage;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Project user and assignee tools. These never accept or return a Polarion token. */
public final class UserTools {

  static final String LIST_PROJECT_USERS =
      """
      List Polarion users for a project, filtered by project role.

      Call this before assign_work_item to pick valid user ids (for example
      users with the project_assignable role).\
      """;

  static final String ASSIGN_WORK_ITEM =
      """
      Assign Polarion users to a work item.

      Call list_project_users first to choose assignable user ids. Default
      dry_run=true only returns the PATCH body; set dry_run=false to apply.
      Pass an empty user_ids list to clear assignees.\
      """;

  private final PolarionClient client;

  public UserTools(PolarionClient client) {
    this.client = client;
  }

  @Tool(name = "list_project_users", description = LIST_PROJECT_USERS)
  public ProjectUserPage listProjectUsers(
      String project_id,
      @ToolParam(required = false) String role,
      @ToolParam(required = false) Integer page_size,
      @ToolParam(required = false) Integer page_number) {
    int pageNumber = page_number == null ? 1 : page_number;
    try {
      return new ProjectUserPage(
          client.listProjectUsers(project_id, role, page_size, pageNumber), pageNumber);
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }

  @Tool(name = "assign_work_item", description = ASSIGN_WORK_ITEM)
  public Object assignWorkItem(
      String project_id,
      String work_item_id,
      List<String> user_ids,
      @ToolParam(required = false) Boolean dry_run) {
    try {
      return client.assignWorkItem(project_id, work_item_id, user_ids, ToolSupport.dryRun(dry_run));
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }
}
