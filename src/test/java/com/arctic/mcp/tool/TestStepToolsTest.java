package com.arctic.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arctic.mcp.polarion.PolarionResources.TestStepAppendPreview;
import com.arctic.mcp.polarion.PolarionResources.TestStepTable;
import com.arctic.mcp.polarion.PolarionResources.TestStepsAppended;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class TestStepToolsTest {

  @Test
  void blankWorkItemIdDoesNotCallPolarion() {
    PolarionHttp http = PolarionHttp.open();

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new TestStepTools(http.client)
                    .setTestSteps("ELK", "  ", List.of(Map.of("step", "Power on")), null, false));

    assertEquals("project_id and work_item_id must be non-empty.", error.getMessage());
    http.server.verify();
  }

  @Test
  void emptyStepsDoNotCallPolarion() {
    PolarionHttp http = PolarionHttp.open();

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new TestStepTools(http.client)
                    .setTestSteps("ELK", "ELK-1", List.of(), null, false));

    assertEquals("steps must contain at least one row.", error.getMessage());
    http.server.verify();
  }

  @Test
  void missingWorkItemStaysNotFound() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-9/teststeps")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"gone\"}]}"));
    http.server
        .expect(
            requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-9?")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"gone\"}]}"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new TestStepTools(http.client)
                    .setTestSteps(
                        "ELK", "ELK-9", List.of(Map.of("step", "Power on")), null, false));

    assertEquals(
        "Polarion request failed (404): Work item not found: ELK/ELK-9", error.getMessage());
    http.server.verify();
  }

  @Test
  void requirementWithoutTestStepsNamesItsType() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-12/teststeps")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"no test steps\"}]}"));
    http.server
        .expect(
            requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-12?")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"workitems","id":"ELK/ELK-12","attributes":{"title":"Brake","type":"requirement","status":"approved"}}}
                """,
                MediaType.APPLICATION_JSON));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new TestStepTools(http.client)
                    .setTestSteps(
                        "ELK", "ELK-12", List.of(Map.of("step", "Power on")), null, false));

    assertEquals(
        "Work item ELK/ELK-12 (type requirement) has no Test Steps table. It is not a test case, or"
            + " the Test Steps field is missing or hidden.",
        error.getMessage());
    http.server.verify();
  }

  @Test
  void emptyTestCaseUsesTheProjectColumnListAndSkipsHiddenColumns() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                PolarionHttp.ROOT
                    + "/projects/ELK/workitems/ELK-1/teststeps?fields%5Bteststeps%5D=%40all"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/enumerations/testing/test-step-keys/~")))
        .andExpect(method(HttpMethod.GET))
        .andExpect(PolarionHttp.query("fields[enumerations]", "@all"))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"enumerations","id":"ELK/testing/test-step-keys/~","attributes":{"options":[
                  {"id":"step","name":"Step"},
                  {"id":"description","name":"Description"},
                  {"id":"expectedResult","name":"Expected Result"},
                  {"id":"comment","name":"Comment","hidden":true}
                ]}}}
                """,
                MediaType.APPLICATION_JSON));

    TestStepTable table = new TestStepTools(http.client).getTestSteps("ELK", "ELK-1");

    assertEquals("ELK", table.projectId());
    assertEquals("ELK/ELK-1", table.workItemId());
    assertEquals(List.of("step", "description", "expectedResult"), table.columns());
    assertEquals(List.of(), table.steps());
    assertEquals("empty", table.stepsVersion());
    http.server.verify();
  }

  @Test
  void missingColumnEnumerationUsesOneSiblingOfTheSameType() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1/teststeps")))
        .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/enumerations/testing/test-step-keys/~")))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"missing\"}]}"));
    http.server
        .expect(
            requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1?")))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"workitems","id":"ELK/ELK-1","attributes":{"type":"testcase"}}}
                """,
                MediaType.APPLICATION_JSON));
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems?")))
        .andExpect(PolarionHttp.query("query", "type:testcase"))
        .andExpect(PolarionHttp.query("page[size]", "20"))
        .andRespond(
            withSuccess(
                """
                {"data":[
                  {"type":"workitems","id":"ELK/ELK-1","attributes":{"type":"testcase"}},
                  {"type":"workitems","id":"ELK/ELK-2","attributes":{"type":"testcase"}}
                ]}
                """,
                MediaType.APPLICATION_JSON));
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-2/teststeps")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"teststeps","attributes":{"keys":["action","outcome"],"values":[
                  {"type":"text/html","value":"Press"},{"type":"text/html","value":"Beep"}
                ]}}]}
                """,
                MediaType.APPLICATION_JSON));

    TestStepTable table = new TestStepTools(http.client).getTestSteps("ELK", "ELK-1");

    assertEquals(List.of("action", "outcome"), table.columns());
    assertEquals(List.of(), table.steps());
    http.server.verify();
  }

  @Test
  void dryRunPreviewsNewRowsAndDoesNotPost() {
    PolarionHttp http = PolarionHttp.open();
    emptyCaseWithDefaultColumns(http);

    TestStepAppendPreview preview =
        (TestStepAppendPreview)
            new TestStepTools(http.client)
                .setTestSteps(
                    "ELK",
                    "ELK-1",
                    List.of(
                        Map.of(
                            "step", "Power on",
                            "description", "Apply ignition",
                            "expectedResult", "Standby")),
                    null,
                    null);

    assertTrue(preview.dryRun());
    assertEquals(List.of("step", "description", "expectedResult"), preview.columns());
    assertEquals(0, preview.existingCount());
    assertFalse(preview.alreadyStored());
    assertEquals("Power on", preview.steps().get(0).get("step"));
    http.server.verify();
  }

  @Test
  void applyAppendsOnlyTheNewRows() {
    PolarionHttp http = PolarionHttp.open();
    emptyCaseWithDefaultColumns(http);
    http.server
        .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1/teststeps"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"data":[{"type":"teststeps","attributes":{"keys":["step","description","expectedResult"],"values":[
                      {"type":"text/html","value":"Power on"},
                      {"type":"text/html","value":"Apply ignition"},
                      {"type":"text/html","value":"Standby"}
                    ]}}]}
                    """))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\":[]}"));

    TestStepsAppended stored =
        (TestStepsAppended)
            new TestStepTools(http.client)
                .setTestSteps(
                    "ELK",
                    "ELK-1",
                    List.of(step("Power on", "Apply ignition", "Standby")),
                    null,
                    false);

    assertEquals(1, stored.stepCount());
    assertEquals(0, stored.existingCount());
    assertFalse(stored.alreadyStored());
    http.server.verify();
  }

  @Test
  void stepsVersionChangesWhenTheTableChanges() {
    PolarionHttp http = PolarionHttp.open();
    savedStep(http, "Alpha");
    savedStep(http, "Beta");
    TestStepTools tools = new TestStepTools(http.client);
    String first = tools.getTestSteps("ELK", "ELK-1").stepsVersion();
    String second = tools.getTestSteps("ELK", "ELK-1").stepsVersion();

    assertNotEquals("empty", first);
    assertNotEquals(first, second);
    http.server.verify();
  }

  @Test
  void existingStepsRequireTheVersionBeforeAppend() {
    PolarionHttp http = PolarionHttp.open();
    savedStep(http, "Alpha");

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new TestStepTools(http.client)
                    .setTestSteps(
                        "ELK",
                        "ELK-1",
                        List.of(step("Beta", "Apply ignition", "Standby")),
                        null,
                        false));

    assertEquals(
        "ELK/ELK-1 already has 1 test step; pass expected_version from get_test_steps.",
        error.getMessage());
    http.server.verify();
  }

  @Test
  void appendingRowsAlreadyAtTheBottomDoesNotPost() {
    PolarionHttp http = PolarionHttp.open();
    savedStep(http, "Alpha");

    TestStepsAppended stored =
        (TestStepsAppended)
            new TestStepTools(http.client)
                .setTestSteps(
                    "ELK",
                    "ELK-1",
                    List.of(step("Alpha", "Apply ignition", "Standby")),
                    null,
                    false);

    assertTrue(stored.alreadyStored());
    assertEquals(1, stored.stepCount());
    http.server.verify();
  }

  @Test
  void aStepWithTheWrongColumnsDoesNotPost() {
    PolarionHttp http = PolarionHttp.open();
    emptyCaseWithDefaultColumns(http);

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                new TestStepTools(http.client)
                    .setTestSteps("ELK", "ELK-1", List.of(Map.of("step", "Only")), null, false));

    assertEquals(
        "Test step 1 must use columns [step, description, expectedResult] and no others.",
        error.getMessage());
    http.server.verify();
  }

  @Test
  void siblingsWithDifferentColumnsFail() {
    PolarionHttp http = PolarionHttp.open();
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1/teststeps")))
        .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/enumerations/testing/test-step-keys/~")))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"detail\":\"missing\"}]}"));
    http.server
        .expect(
            requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1?")))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"workitems","id":"ELK/ELK-1","attributes":{"type":"testcase"}}}
                """,
                MediaType.APPLICATION_JSON));
    http.server
        .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems?")))
        .andRespond(
            withSuccess(
                """
                {"data":[
                  {"type":"workitems","id":"ELK/ELK-2","attributes":{"type":"testcase"}},
                  {"type":"workitems","id":"ELK/ELK-3","attributes":{"type":"testcase"}}
                ]}
                """,
                MediaType.APPLICATION_JSON));
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-2/teststeps")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"teststeps","attributes":{"keys":["action"],"values":[{"type":"text/html","value":"Press"}]}}]}
                """,
                MediaType.APPLICATION_JSON));
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-3/teststeps")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"teststeps","attributes":{"keys":["outcome"],"values":[{"type":"text/html","value":"Beep"}]}}]}
                """,
                MediaType.APPLICATION_JSON));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> new TestStepTools(http.client).getTestSteps("ELK", "ELK-1"));

    assertEquals(
        "Test step columns for type testcase disagree across work items.", error.getMessage());
    http.server.verify();
  }

  private static void savedStep(PolarionHttp http, String step) {
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1/teststeps")))
        .andRespond(
            withSuccess(
                """
                {"data":[{"type":"teststeps","attributes":{"keys":["step","description","expectedResult"],"values":[
                  {"type":"text/html","value":"%s"},
                  {"type":"text/html","value":"Apply ignition"},
                  {"type":"text/html","value":"Standby"}
                ]}}]}
                """
                    .formatted(step),
                MediaType.APPLICATION_JSON));
  }

  private static Map<String, String> step(String name, String description, String expected) {
    return Map.of("step", name, "description", description, "expectedResult", expected);
  }

  private static void emptyCaseWithDefaultColumns(PolarionHttp http) {
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-1/teststeps")))
        .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
    http.server
        .expect(
            requestTo(
                Matchers.startsWith(
                    PolarionHttp.ROOT + "/projects/ELK/enumerations/testing/test-step-keys/~")))
        .andRespond(
            withSuccess(
                """
                {"data":{"type":"enumerations","attributes":{"options":[
                  {"id":"step","name":"Step"},
                  {"id":"description","name":"Description"},
                  {"id":"expectedResult","name":"Expected Result"}
                ]}}}
                """,
                MediaType.APPLICATION_JSON));
  }
}
