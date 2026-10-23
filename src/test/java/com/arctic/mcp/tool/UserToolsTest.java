package com.arctic.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arctic.mcp.polarion.PolarionResources.AssignedWorkItem;
import com.arctic.mcp.polarion.PolarionResources.ProjectUser;
import com.arctic.mcp.polarion.PolarionResources.WorkItemAssignPreview;
import com.arctic.mcp.polarion.ProjectAllowlist;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class UserToolsTest {

  @Test
  void listProjectUsersKeepsOnlyTheRequestedRole() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/users?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(PolarionHttp.query("fields[users]", "id,name,email,projectRoles"))
        .andExpect(PolarionHttp.query("include", "projectRoles"))
        .andRespond(
            withSuccess(
                """
                {"data":[
                  {"type":"users","id":"alice","attributes":{"name":"Alice","email":"alice@example.com"},
                   "relationships":{"projectRoles":{"data":[{"type":"projectroles","id":"ELK/project_assignable"}]}}},
                  {"type":"users","id":"bob","attributes":{"name":"Bob"},
                   "relationships":{"projectRoles":{"data":[{"type":"projectroles","id":"ELK/project_admin"}]}}}
                ]}
                """,
                MediaType.APPLICATION_JSON));

    List<ProjectUser> users =
        new UserTools(http.client).listProjectUsers("ELK", null, null, null).users();

    assertEquals(1, users.size());
    assertEquals("alice", users.get(0).id());
    assertEquals("alice@example.com", users.get(0).email());
    http.server.verify();
  }

  @Test
  void assignDryRunReplacesTheAssigneeList() {
    PolarionHttp http = PolarionHttp.open();

    WorkItemAssignPreview preview =
        (WorkItemAssignPreview)
            new UserTools(http.client).assignWorkItem("ELK", "ELK-42", List.of(), null);

    assertTrue(preview.dryRun());
    @SuppressWarnings("unchecked")
    List<?> assignees =
        (List<?>)
            ((Map<?, ?>)
                    ((Map<?, ?>) ((Map<?, ?>) preview.body().get("data")).get("relationships"))
                        .get("assignee"))
                .get("data");
    assertTrue(assignees.isEmpty());
    http.server.verify();
  }

  @Test
  void assignApplyReturnsTheWorkItemId() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-42"))
        .andExpect(method(HttpMethod.PATCH))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    AssignedWorkItem assigned =
        (AssignedWorkItem)
            new UserTools(http.client)
                .assignWorkItem("ELK", "ELK-42", List.of("alice", "bob"), false);

    assertEquals("ELK/ELK-42", assigned.id());
    http.server.verify();
  }

  @Test
  void disallowedProjectIsRefused() {
    PolarionHttp http = PolarionHttp.open(ProjectAllowlist.parse("ELK"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> new UserTools(http.client).listProjectUsers("OTHER", null, null, null));

    assertTrue(error.getMessage().contains("POLARION_PROJECT_ALLOWLIST"));
    http.server.verify();
  }

  @Test
  void rejectedTokenDoesNotEchoTheToken() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/users?")))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"errors\":[{\"detail\":\"" + PolarionHttp.TOKEN + "\"}]}"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> new UserTools(http.client).listProjectUsers("ELK", null, null, null));

    assertFalse(error.getMessage().contains(PolarionHttp.TOKEN));
  }
}
