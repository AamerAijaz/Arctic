package com.arctic.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arctic.mcp.polarion.PolarionResources.CreatedWorkItemLink;
import com.arctic.mcp.polarion.PolarionResources.LinkRole;
import com.arctic.mcp.polarion.PolarionResources.WorkItemLink;
import com.arctic.mcp.polarion.PolarionResources.WorkItemLinkDeletePreview;
import com.arctic.mcp.polarion.PolarionResources.WorkItemLinkPreview;
import com.arctic.mcp.polarion.ProjectAllowlist;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class LinkToolsTest {

  @Test
  void listLinkRolesReturnsRules() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/enumerations/~/workitem-link-role/~?")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(PolarionHttp.query("fields[enumerations]", "@all"))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"enumerations","attributes":{"options":[
                  {"id":"implements","name":"implements","oppositeName":"is implemented by",
                   "linkRules":[{"fromTypes":["task"],"toTypes":["requirement"]}]}
                ]}}}
                """,
                MediaType.APPLICATION_JSON));

    LinkRole role = new LinkTools(http.client).listLinkRoles("ELK").roles().get(0);

    assertEquals("implements", role.id());
    assertEquals("is implemented by", role.oppositeName());
    assertEquals(List.of("task"), role.linkRules().get(0).get("fromTypes"));
    http.server.verify();
  }

  @Test
  void listWorkItemLinksIncludesTheTargetTitle() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-42/linkedworkitems?")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"linkedworkitems","id":"ELK/ELK-42/implements/ELK/ELK-12","attributes":{"role":"implements"},
                  "relationships":{"workItem":{"data":{"type":"workitems","id":"ELK/ELK-12"}}}}],
                 "included":[{"type":"workitems","id":"ELK/ELK-12","attributes":{"title":"Requirement"}}]}
                """,
                MediaType.APPLICATION_JSON));

    WorkItemLink link =
        new LinkTools(http.client).listWorkItemLinks("ELK", "ELK-42").links().get(0);

    assertEquals("implements", link.role());
    assertEquals("ELK/ELK-12", link.targetId());
    assertEquals("Requirement", link.targetTitle());
  }

  @Test
  void listBacklinksUsesTheBacklinkCollection() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-12/backlinkedworkitems?")))
        .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

    assertTrue(new LinkTools(http.client).listWorkItemBacklinks("ELK", "ELK-12").links().isEmpty());
    http.server.verify();
  }

  @Test
  void createLinkDryRunReturnsThePostBody() {
    PolarionHttp http = PolarionHttp.open();

    WorkItemLinkPreview preview =
        (WorkItemLinkPreview)
            new LinkTools(http.client)
                .createWorkItemLink("ELK", "ELK-42", "ELK-12", "implements", null, null);

    assertTrue(preview.dryRun());
    assertEquals(
        Map.of(
            "data",
            List.of(
                Map.of(
                    "type",
                    "linkedworkitems",
                    "attributes",
                    Map.of("role", "implements"),
                    "relationships",
                    Map.of(
                        "workItem",
                        Map.of("data", Map.of("type", "workitems", "id", "ELK/ELK-12")))))),
        preview.body());
    http.server.verify();
  }

  @Test
  void createLinkRefusesATargetProjectOutsideTheAllowlist() {
    PolarionHttp http = PolarionHttp.open(ProjectAllowlist.parse("ELK"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new LinkTools(http.client)
                    .createWorkItemLink("ELK", "ELK-42", "OTHER-1", "implements", "OTHER", false));

    assertTrue(error.getMessage().contains("Project OTHER is not in POLARION_PROJECT_ALLOWLIST."));
    http.server.verify();
  }

  @Test
  void createLinkApplyReturnsTheLinkId() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-42/linkedworkitems"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"linkedworkitems","id":"ELK/ELK-42/implements/ELK/ELK-12"}]}
                """,
                MediaType.APPLICATION_JSON));

    CreatedWorkItemLink created =
        (CreatedWorkItemLink)
            new LinkTools(http.client)
                .createWorkItemLink("ELK", "ELK-42", "ELK-12", "implements", null, false);

    assertEquals("ELK/ELK-42/implements/ELK/ELK-12", created.id());
    http.server.verify();
  }

  @Test
  void deleteLinkDryRunReturnsTheDeletePath() {
    PolarionHttp http = PolarionHttp.open();

    WorkItemLinkDeletePreview preview =
        (WorkItemLinkDeletePreview)
            new LinkTools(http.client)
                .deleteWorkItemLink("ELK", "ELK-42", "ELK-12", "implements", null, null);

    assertEquals(
        "/projects/ELK/workitems/ELK-42/linkedworkitems/implements/ELK/ELK-12", preview.path());
    assertEquals("ELK", preview.targetProjectId());
    http.server.verify();
  }

  @Test
  void missingLinkIsNamedInTheError() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-42/linkedworkitems/")))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND).body("{\"errors\":[{\"title\":\"Not Found\"}]}"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new LinkTools(http.client)
                    .deleteWorkItemLink("ELK", "ELK-42", "ELK-12", "implements", null, false));

    assertEquals(
        "Polarion request failed (404): Link not found: ELK/ELK-42/implements/ELK/ELK-12",
        error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void rejectedTokenDoesNotEchoTheToken() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/enumerations/")))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"errors\":[{\"detail\":\"" + PolarionHttp.TOKEN + "\"}]}"));

    RuntimeException error =
        assertThrows(RuntimeException.class, () -> new LinkTools(http.client).listLinkRoles("ELK"));

    assertFalse(error.getMessage().contains(PolarionHttp.TOKEN));
  }
}
