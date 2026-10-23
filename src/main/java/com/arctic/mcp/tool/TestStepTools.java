package com.arctic.mcp.tool;

import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionException;
import com.arctic.mcp.polarion.PolarionResources.TestStepTable;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Test Steps on a Polarion work item. These never accept or return a Polarion token. */
public final class TestStepTools {

  static final String GET_TEST_STEPS =
      """
      Read the Test Steps table on a work item.

      Fails when the work item is missing, is not a test case, or has no
      Test Steps field. `columns` are that project's configured keys and
      can differ by project. Do not assume step, description, and
      expectedResult. `steps_version` is `empty` when there are no rows;
      pass it as expected_version when adding steps to a table that
      already has rows. Read linked work items with list_work_item_links,
      list_work_item_backlinks, and get_work_item before writing step text.\
      """;

  static final String SET_TEST_STEPS =
      """
      Append rows to the Test Steps table. Existing rows stay.

      Call get_test_steps first. `steps` is only the new rows, and each
      row's keys must match `columns` exactly. Cell text is stored as
      HTML. Default dry_run=true previews the rows and does not write;
      set dry_run=false to append. When the table already has rows, pass
      expected_version from get_test_steps. A repeat of the same rows
      does not append them again. Polarion cannot append in place: a
      write deletes the current rows and posts them back with the new
      ones. Blank cells are stored as empty text.\
      """;

  private final PolarionClient client;

  public TestStepTools(PolarionClient client) {
    this.client = client;
  }

  @Tool(name = "get_test_steps", description = GET_TEST_STEPS)
  public TestStepTable getTestSteps(String project_id, String work_item_id) {
    try {
      return client.getTestSteps(project_id, work_item_id);
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }

  @Tool(name = "set_test_steps", description = SET_TEST_STEPS)
  public Object setTestSteps(
      String project_id,
      String work_item_id,
      List<Map<String, String>> steps,
      @ToolParam(required = false) String expected_version,
      @ToolParam(required = false) Boolean dry_run) {
    try {
      return client.appendTestSteps(
          project_id, work_item_id, steps, expected_version, ToolSupport.dryRun(dry_run));
    } catch (PolarionException exception) {
      throw ToolSupport.failed(exception);
    }
  }
}
