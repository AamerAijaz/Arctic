package com.arctic.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arctic.mcp.polarion.PolarionResources.CreatedWorkItem;
import com.arctic.mcp.polarion.PolarionResources.CreatedWorkItemType;
import com.arctic.mcp.polarion.PolarionResources.UpdatedWorkItem;
import com.arctic.mcp.polarion.PolarionResources.WorkItem;
import com.arctic.mcp.polarion.PolarionResources.WorkItemCreatePreview;
import com.arctic.mcp.polarion.PolarionResources.WorkItemPage;
import com.arctic.mcp.polarion.PolarionResources.WorkItemTypeCreatePreview;
import com.arctic.mcp.polarion.PolarionResources.WorkItemUpdatePreview;
import com.arctic.mcp.polarion.ProjectAllowlist;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class WorkItemToolsTest {

  private static final String TYPE_ENUM =
      """
      {"data":{"type":"enumerations","id":"ELK/~/workitem-type/~","attributes":{"options":[
        {"id":"hardware","name":"hw","color":"#111111","iconURL":"/polarion/icons/default/enums/type_hardware.gif"},
        {"id":"softwarerequirement","name":"Software Requirement","color":"#222222","iconURL":"/polarion/icons/default/enums/type_software.gif"}
      ]}}}
      """;

  @Test
  void listWorkItemsSendsTheLuceneQueryAndOmitsDescription() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(PolarionHttp.query("fields[workitems]", "id,title,type,status"))
        .andExpect(PolarionHttp.query("query", "type:requirement"))
        .andExpect(PolarionHttp.query("page[size]", "100"))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"workitems","id":"ELK/ELK-1","attributes":{"title":"Req","type":"requirement","status":"draft"}}]}
                """,
                MediaType.APPLICATION_JSON));

    WorkItemPage page =
        new WorkItemTools(http.client).listWorkItems("ELK", "type:requirement", null, null);

    WorkItem item = page.workItems().get(0);
    assertEquals("ELK/ELK-1", item.id());
    assertEquals("requirement", item.type());
    assertNull(item.description());
    assertTrue(item.assigneeIds().isEmpty());
    http.server.verify();
  }

  @Test
  void getWorkItemIncludesDescriptionAndAssignees() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1?")))
        .andExpect(
            PolarionHttp.query("fields[workitems]", "id,title,type,status,description,assignee"))
        .andExpect(PolarionHttp.query("include", "assignee"))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"workitems","id":"ELK/ELK-1","attributes":{"title":"Req","description":{"type":"text/html","value":"<p>Body</p>"}},
                  "relationships":{"assignee":{"data":[{"type":"users","id":"alice"}]}}}}
                """,
                MediaType.APPLICATION_JSON));

    WorkItem item = new WorkItemTools(http.client).getWorkItem("ELK", "ELK-1");

    assertEquals("<p>Body</p>", item.description());
    assertEquals(List.of("alice"), item.assigneeIds());
  }

  @Test
  void createWorkItemDryRunDoesNotPost() {
    PolarionHttp http = PolarionHttp.open();

    WorkItemCreatePreview preview =
        (WorkItemCreatePreview)
            new WorkItemTools(http.client)
                .createWorkItem("ELK", "task", "Fix login", "<p>SSO timeout</p>", null);

    assertTrue(preview.dryRun());
    assertEquals("ELK", preview.projectId());
    assertEquals(
        Map.of(
            "data",
            List.of(
                Map.of(
                    "type",
                    "workitems",
                    "attributes",
                    Map.of(
                        "type",
                        "task",
                        "title",
                        "Fix login",
                        "description",
                        Map.of("type", "text/html", "value", "<p>SSO timeout</p>"))))),
        preview.body());
    http.server.verify();
  }

  @Test
  void createWorkItemApplyReturnsTheIdAndPortalUrl() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"data":[{"type":"workitems","attributes":{"type":"task","title":"Fix login"}}]}
                    """))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"workitems","id":"ELK/ELK-42","links":{"portal":"https://polarion.example.com/polarion/#/project/ELK/workitem?id=ELK-42"}}]}
                """,
                MediaType.APPLICATION_JSON));

    CreatedWorkItem created =
        (CreatedWorkItem)
            new WorkItemTools(http.client).createWorkItem("ELK", "task", "Fix login", null, false);

    assertEquals("ELK/ELK-42", created.id());
    assertEquals(
        "https://polarion.example.com/polarion/#/project/ELK/workitem?id=ELK-42",
        created.portalUrl());
    http.server.verify();
  }

  @Test
  void updateWithoutFieldsIsRefused() {
    PolarionHttp http = PolarionHttp.open();

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new WorkItemTools(http.client)
                    .updateWorkItem("ELK", "ELK-1", null, null, null, null, false));

    assertEquals(
        "Provide at least one of title, description, status, or change_type_to.",
        error.getMessage());
    http.server.verify();
  }

  @Test
  void updateDryRunDoesNotPatch() {
    PolarionHttp http = PolarionHttp.open();

    WorkItemUpdatePreview preview =
        (WorkItemUpdatePreview)
            new WorkItemTools(http.client)
                .updateWorkItem(
                    "ELK",
                    "ELK/ELK-42",
                    "Fix login again",
                    "<p>Updated</p>",
                    "in_progress",
                    null,
                    null);

    assertEquals("ELK-42", preview.workItemId());
    assertNull(preview.changeTypeTo());
    assertEquals("ELK/ELK-42", ((Map<?, ?>) preview.body().get("data")).get("id"));
    http.server.verify();
  }

  @Test
  void updateChangeTypeSendsChangeTypeTo() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-5?")))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(PolarionHttp.query("changeTypeTo", "hardware"))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    UpdatedWorkItem updated =
        (UpdatedWorkItem)
            new WorkItemTools(http.client)
                .updateWorkItem("ELK", "ELK-5", null, null, null, "hardware", false);

    assertEquals("ELK/ELK-5", updated.id());
    http.server.verify();
  }

  @Test
  void createWorkItemTypeDryRunAppendsTheOptionAndDoesNotPatch() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/enumerations/~/workitem-type/~")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(PolarionHttp.query("fields[enumerations]", "@all"))
        .andRespond(withSuccess(TYPE_ENUM, MediaType.APPLICATION_JSON));

    WorkItemTypeCreatePreview preview =
        (WorkItemTypeCreatePreview)
            new WorkItemTools(http.client)
                .createWorkItemType(
                    "ELK", "ux", "UX", "User experience requirement.", null, null, null);

    assertFalse(preview.alreadyExists());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> options =
        (List<Map<String, Object>>)
            ((Map<?, ?>) ((Map<?, ?>) preview.body().get("data")).get("attributes")).get("options");
    assertEquals(
        List.of("hardware", "softwarerequirement", "ux"),
        options.stream().map(option -> option.get("id")).toList());
    assertEquals("#111111", options.get(0).get("color"));
    assertEquals("#222222", options.get(2).get("color"));
    assertEquals("/polarion/icons/default/enums/type_software.gif", options.get(2).get("iconURL"));
    http.server.verify();
  }

  @Test
  void createWorkItemTypeAlreadyExistsDoesNotPatch() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/enumerations/")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(TYPE_ENUM, MediaType.APPLICATION_JSON));

    CreatedWorkItemType created =
        (CreatedWorkItemType)
            new WorkItemTools(http.client)
                .createWorkItemType("ELK", "hardware", "hw", null, null, null, false);

    assertTrue(created.alreadyExists());
    assertEquals("hardware", created.id());
    http.server.verify();
  }

  @Test
  void createWorkItemTypeRejectsSpacesBeforeCallingPolarion() {
    PolarionHttp http = PolarionHttp.open();

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new WorkItemTools(http.client)
                    .createWorkItemType("ELK", "software mitigation", "X", null, null, null, null));

    assertTrue(error.getMessage().contains("no spaces"));
    http.server.verify();
  }

  @Test
  void disallowedProjectIsRefused() {
    PolarionHttp http = PolarionHttp.open(ProjectAllowlist.parse("ELK"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> new WorkItemTools(http.client).listWorkItems("OTHER", null, null, null));

    assertTrue(error.getMessage().contains("OTHER"));
    assertTrue(error.getMessage().contains("POLARION_PROJECT_ALLOWLIST"));
    http.server.verify();
  }

  @Test
  void rejectedTokenDoesNotEchoTheToken() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems?")))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"errors\":[{\"detail\":\"" + PolarionHttp.TOKEN + "\"}]}"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> new WorkItemTools(http.client).listWorkItems("ELK", null, null, null));

    assertFalse(error.getMessage().contains(PolarionHttp.TOKEN));
    assertInstanceOf(RuntimeException.class, error);
  }
}
