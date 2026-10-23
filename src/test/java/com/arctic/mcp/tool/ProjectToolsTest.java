package com.arctic.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arctic.mcp.polarion.PolarionResources.Project;
import com.arctic.mcp.polarion.PolarionResources.ProjectPage;
import com.arctic.mcp.polarion.ProjectAllowlist;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class ProjectToolsTest {

  @Test
  void listProjectsReturnsIdsAndSendsTheSparseFieldset() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + PolarionHttp.TOKEN))
        .andExpect(
            PolarionHttp.query("fields[projects]", "id,name,description,active,trackerPrefix"))
        .andExpect(PolarionHttp.query("page[size]", "100"))
        .andExpect(PolarionHttp.query("page[number]", "1"))
        .andExpect(PolarionHttp.query("query", "name:Elk*"))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"projects","id":"ELK","attributes":{"name":"Elk","active":true,"trackerPrefix":"ELK"}}]}
                """,
                MediaType.APPLICATION_JSON));

    ProjectPage page = new ProjectTools(http.client).listProjects("name:Elk*", null, null);

    assertEquals(1, page.pageNumber());
    Project project = page.projects().get(0);
    assertEquals("ELK", project.id());
    assertEquals("Elk", project.name());
    assertNull(project.description());
    assertEquals(true, project.active());
    assertEquals("ELK", project.trackerPrefix());
    http.server.verify();
  }

  @Test
  void listProjectsHidesProjectsOutsideTheAllowlist() {
    PolarionHttp http = PolarionHttp.open(ProjectAllowlist.parse(" ELK, ,OTHER "));
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects?")))
        .andRespond(
            withSuccess(
                """
                {"data":[
                  {"type":"projects","id":"ELK","attributes":{"name":"Elk"}},
                  {"type":"projects","id":"HIDDEN","attributes":{"name":"Hidden"}}
                ]}
                """,
                MediaType.APPLICATION_JSON));

    ProjectPage page = new ProjectTools(http.client).listProjects(null, 10, 2);

    assertEquals(1, page.projects().size());
    assertEquals("ELK", page.projects().get(0).id());
    assertEquals(2, page.pageNumber());
    http.server.verify();
  }

  @Test
  void getProjectReturnsOneProject() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK?")))
        .andExpect(
            PolarionHttp.query("fields[projects]", "id,name,description,active,trackerPrefix"))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"projects","id":"ELK","attributes":{"name":"Elk","description":{"type":"text/plain","value":"A project"},"active":false}}}
                """,
                MediaType.APPLICATION_JSON));

    Project project = new ProjectTools(http.client).getProject("ELK");

    assertEquals("ELK", project.id());
    assertEquals("A project", project.description());
    assertEquals(false, project.active());
    http.server.verify();
  }

  @Test
  void missingProjectIsNamedInTheError() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/missing")))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND).body("{\"errors\":[{\"title\":\"Not Found\"}]}"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class, () -> new ProjectTools(http.client).getProject("missing"));

    assertEquals("Polarion request failed (404): Project not found: missing", error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void disallowedProjectIsRefusedBeforePolarionIsCalled() {
    PolarionHttp http = PolarionHttp.open(ProjectAllowlist.parse("ELK"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class, () -> new ProjectTools(http.client).getProject("OTHER"));

    assertEquals(
        "Polarion request failed (403): Project OTHER is not in POLARION_PROJECT_ALLOWLIST.",
        error.getMessage());
    http.server.verify();
  }

  @Test
  void rejectedTokenDoesNotEchoTheToken() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects?")))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"" + PolarionHttp.TOKEN + "\"}]}"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> new ProjectTools(http.client).listProjects(null, null, null));

    assertEquals(
        "Polarion rejected the token. Create a personal access token in Polarion and set"
            + " POLARION_TOKEN.",
        error.getMessage());
    assertFalse(error.getMessage().contains(PolarionHttp.TOKEN));
  }

  @Test
  void emptyAllowlistLeavesEveryProjectVisible() {
    assertTrue(ProjectAllowlist.parse(null).allows("ANY"));
    assertTrue(ProjectAllowlist.parse("  ").allows("ANY"));
    assertFalse(ProjectAllowlist.parse("ELK").allows("OTHER"));
  }
}
