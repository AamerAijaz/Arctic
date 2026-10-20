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

import com.arctic.mcp.polarion.PolarionResources.CreatedDocument;
import com.arctic.mcp.polarion.PolarionResources.CreatedWorkItem;
import com.arctic.mcp.polarion.PolarionResources.CreatedDocumentWorkItem;
import com.arctic.mcp.polarion.PolarionResources.Document;
import com.arctic.mcp.polarion.PolarionResources.DocumentCreatePreview;
import com.arctic.mcp.polarion.PolarionResources.DocumentImportPreview;
import com.arctic.mcp.polarion.PolarionResources.DocumentPart;
import com.arctic.mcp.polarion.PolarionResources.DocumentPartDeletePreview;
import com.arctic.mcp.polarion.PolarionResources.DocumentPartMovePreview;
import com.arctic.mcp.polarion.PolarionResources.DocumentWorkItemCreatePreview;
import com.arctic.mcp.polarion.PolarionResources.RequirementWorkItemPreview;
import com.arctic.mcp.polarion.ProjectAllowlist;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class DocumentToolsTest {

    @Test
    void listDocumentsBuildsAPortalUrlThatOmitsTheDefaultSpace() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents?")))
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"documents","id":"ELK/_default/Specs","attributes":{"moduleName":"Specs","title":"Specifications","type":"req_specification"}}]}
                        """,
                        MediaType.APPLICATION_JSON));

        Document document = new DocumentTools(http.client).listDocuments("ELK", null, null, null).documents().get(0);

        assertEquals("Specs", document.moduleName());
        assertEquals("https://polarion.example.com/polarion/#/project/ELK/wiki/Specs", document.portalUrl());
        assertNull(document.contentSummary());
        http.server.verify();
    }

    @Test
    void getDocumentStripsAndCapsTheHomePageSummary() {
        PolarionHttp http = PolarionHttp.open();
        String longText = "a".repeat(450);
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs?fields%5Bdocuments%5D=id%2CmoduleName%2Ctitle%2Ctype%2Cstatus%2ChomePageContent"))
                .andRespond(withSuccess(
                        """
                        {"data":{"type":"documents","id":"ELK/_default/Specs","attributes":{"moduleName":"Specs","homePageContent":{"type":"text/html","value":"<p>%s</p>"}},"links":{"portal":"https://example.test/portal"}}}
                        """.formatted(longText),
                        MediaType.APPLICATION_JSON));

        Document document = new DocumentTools(http.client).getDocument("ELK", "Specs", null);

        assertEquals("https://example.test/portal", document.portalUrl());
        assertEquals(400, document.contentSummary().length());
        assertEquals("a".repeat(400), document.contentSummary());
        http.server.verify();
    }

    @Test
    void missingDocumentIsNamedInTheError() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/missing")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"errors\":[{\"title\":\"Not Found\"}]}"));

        RuntimeException error =
                assertThrows(RuntimeException.class, () -> new DocumentTools(http.client).getDocument("ELK", "missing", null));

        assertEquals("Polarion request failed (404): Document not found: ELK/_default/missing", error.getMessage());
    }

    @Test
    void listDocumentPartsStripsHtmlAndKeepsTheWorkItemId() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts?")))
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"document_parts","id":"ELK/_default/Specs/heading_1","attributes":{"type":"heading","level":1,"content":{"type":"text/html","value":"<p>Body</p>"}},
                          "relationships":{"workItem":{"data":{"type":"workitems","id":"ELK/ELK-5"}}}}]}
                        """,
                        MediaType.APPLICATION_JSON));

        DocumentPart part = new DocumentTools(http.client).listDocumentParts("ELK", "Specs", null, null, null).parts().get(0);

        assertEquals("heading", part.partType());
        assertEquals(1, part.level());
        assertEquals("Body", part.text());
        assertEquals("ELK/ELK-5", part.workItemId());
    }

    @Test
    void createDocumentDryRunOmitsAnEmptyHomePage() {
        PolarionHttp http = PolarionHttp.open();

        DocumentCreatePreview preview = (DocumentCreatePreview) new DocumentTools(http.client)
                .createDocument("ELK", "Bare", "req", "parent", null, "", null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes =
                (Map<String, Object>) ((Map<?, ?>) ((List<?>) preview.body().get("data")).get(0)).get("attributes");
        assertEquals("Bare", attributes.get("moduleName"));
        assertFalse(attributes.containsKey("homePageContent"));
        assertEquals("_default", preview.spaceId());
        http.server.verify();
    }

    @Test
    void createDocumentApplyReturnsAPortalUrl() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"documents","id":"ELK/_default/Specs"}]}
                        """,
                        MediaType.APPLICATION_JSON));

        CreatedDocument created = (CreatedDocument)
                new DocumentTools(http.client).createDocument("ELK", "Specs", "req", "parent", "Specifications", null, null, false);

        assertEquals("ELK/_default/Specs", created.id());
        assertEquals("https://polarion.example.com/polarion/#/project/ELK/wiki/Specs", created.portalUrl());
        http.server.verify();
    }

    @Test
    void moveAndDeleteDryRunsDoNotCallPolarion() {
        PolarionHttp http = PolarionHttp.open();
        DocumentTools tools = new DocumentTools(http.client);

        DocumentPartMovePreview move =
                (DocumentPartMovePreview) tools.moveDocumentPart("ELK", "Specs", "heading_1", "part-2", null, null, null, null);
        DocumentPartDeletePreview delete =
                (DocumentPartDeletePreview) tools.deleteDocumentParts("ELK", "Specs", List.of("text_1"), null, null);

        assertTrue(move.path().endsWith("/parts/heading_1/actions/move"));
        assertEquals("part-2", move.body().get("after"));
        assertTrue(delete.path().endsWith("/documents/Specs/parts"));
        http.server.verify();
    }

    @Test
    void createDocumentWorkItemDryRunSendsNeitherPost() {
        PolarionHttp http = PolarionHttp.open();

        DocumentWorkItemCreatePreview preview = (DocumentWorkItemCreatePreview) new DocumentTools(http.client)
                .createDocumentWorkItem(
                        "ELK", "Specs", "requirement", "REQ-1", null, "<p>Text</p>", null, null, null, "heading_1", null, null, null);

        assertTrue(preview.dryRun());
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) ((List<?>) preview.workItemBody().get("data")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> module = (Map<String, Object>) ((Map<?, ?>) entry.get("relationships")).get("module");
        assertEquals("ELK/_default/Specs", ((Map<?, ?>) module.get("data")).get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> relationships =
                (Map<String, Object>) ((Map<?, ?>) ((List<?>) preview.partBody().get("data")).get(0)).get("relationships");
        assertFalse(relationships.containsKey("workItem"));
        assertEquals("heading_1", ((Map<?, ?>) ((Map<?, ?>) relationships.get("previousPart")).get("data")).get("id"));
        http.server.verify();
    }

    @Test
    void createDocumentWorkItemRejectsBothPreviousAndNext() {
        PolarionHttp http = PolarionHttp.open();

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> new DocumentTools(http.client)
                        .createDocumentWorkItem("ELK", "Specs", "requirement", "REQ-1", null, null, null, null, null, "a", "b", null, null));

        assertEquals("Specify only one of previous_part or next_part.", error.getMessage());
        http.server.verify();
    }

    @Test
    void createDocumentWorkItemApplyPostsTheWorkItemThenThePart() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"workitems","id":"ELK/ELK-9","links":{"portal":"https://example.test/wi"}}]}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"document_parts","id":"ELK/_default/Specs/workitem_ELK-9"}]}
                        """,
                        MediaType.APPLICATION_JSON));

        CreatedDocumentWorkItem created = (CreatedDocumentWorkItem) new DocumentTools(http.client)
                .createDocumentWorkItem("ELK", "Specs", "requirement", "REQ-1", null, null, null, null, null, "heading_1", null, null, false);

        assertEquals("ELK/ELK-9", created.id());
        assertEquals("ELK/_default/Specs/workitem_ELK-9", created.partId());
        assertEquals("https://example.test/wi", created.portalUrl());
        http.server.verify();
    }

    @Test
    void createDocumentWorkItemRemovesCopiedBodyPartsAndKeepsTheHeading() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts?")))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"type":"document_parts","id":"ELK/_default/Specs/heading_1","attributes":{"type":"heading","headingText":"CenTrak"}},
                          {"type":"document_parts","id":"ELK/_default/Specs/polarion_27","attributes":{"type":"normal","content":{"type":"text/html","value":"<p>Global ID: GID-2586182</p>"}}},
                          {"type":"document_parts","id":"ELK/_default/Specs/polarion_28","attributes":{"type":"normal","content":{"type":"text/html","value":"<p>Summary</p>"}}}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"workitems","id":"ELK/ELK-9","links":{"portal":"https://example.test/wi"}}]}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"document_parts","id":"ELK/_default/Specs/workitem_ELK-9"}]}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("polarion_27"), body);
                    assertTrue(body.contains("polarion_28"), body);
                    assertFalse(body.contains("heading_1"), body);
                    assertFalse(body.contains("workitem_ELK-9"), body);
                })
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        CreatedDocumentWorkItem created = (CreatedDocumentWorkItem) new DocumentTools(http.client)
                .createDocumentWorkItem(
                        "ELK",
                        "Specs",
                        "softwarerequirement",
                        "CenTrak Location Zero",
                        null,
                        "<p>Global ID: GID-2586182</p><p>Summary</p>",
                        null,
                        null,
                        null,
                        "heading_1",
                        null,
                        List.of("polarion_27", "polarion_28"),
                        false);

        assertEquals("ELK/ELK-9", created.id());
        assertEquals(List.of("polarion_27", "polarion_28"), created.deleted());
        http.server.verify();
    }

    @Test
    void createDocumentWorkItemRefusesToDeleteAHeading() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts?")))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"type":"document_parts","id":"ELK/_default/Specs/heading_1","attributes":{"type":"heading","headingText":"CenTrak"}},
                          {"type":"document_parts","id":"ELK/_default/Specs/polarion_27","attributes":{"type":"normal"}}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> new DocumentTools(http.client)
                        .createDocumentWorkItem(
                                "ELK",
                                "Specs",
                                "softwarerequirement",
                                "CenTrak",
                                null,
                                "<p>Body</p>",
                                null,
                                null,
                                null,
                                "heading_1",
                                null,
                                List.of("heading_1"),
                                false));

        assertTrue(error.getMessage().contains("Refusing to delete a heading"), error.getMessage());
        http.server.verify();
    }

    @Test
    void importDocumentDryRunDoesNotCreateALiveDoc() {
        PolarionHttp http = PolarionHttp.open();

        DocumentImportPreview preview = (DocumentImportPreview) new DocumentTools(http.client)
                .importDocument(
                        "ELK",
                        "C:\\docs\\spec.docx",
                        "Spec",
                        "req_specification",
                        "Specifications",
                        null,
                        null);

        assertTrue(preview.dryRun());
        assertEquals("spec.docx", preview.filename());
        assertEquals(
                "/projects/ELK/spaces/_default/documents/actions/importWordDocument", preview.path());
        assertEquals("Spec", preview.parameters().get("documentName"));
        assertEquals("req_specification", preview.parameters().get("documentType"));
        assertEquals("Specifications", preview.parameters().get("title"));
        http.server.verify();
    }

    @Test
    void importDocumentSendsTheWordFileAndReturnsTheLiveDocLink(@TempDir Path dir) throws Exception {
        byte[] docx = new byte[] {0x50, 0x4b, 0x03, 0x04, 't', 'a', 'b', 'l', 'e'};
        Path file = dir.resolve("spec.docx");
        Files.write(file, docx);
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/actions/importWordDocument"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("spec.docx"), body);
                    assertTrue(body.contains("table"), body);
                    assertTrue(body.contains("\"documentName\":\"Spec\""), body);
                    assertTrue(body.contains("\"documentType\":\"req_specification\""), body);
                    assertTrue(body.contains("\"title\":\"Specifications\""), body);
                    assertFalse(body.toLowerCase().contains("application/json"), body);
                })
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                """
                                {"data":{"type":"jobs","id":"job-1","attributes":{"state":"RUNNING"}}}
                                """));
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/jobs/job-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"data":{"type":"jobs","id":"job-1","attributes":{"state":"FINISHED","status":{"type":"OK"}}}}
                        """,
                        MediaType.APPLICATION_JSON));

        CreatedDocument created = (CreatedDocument) new DocumentTools(http.client)
                .importDocument("ELK", file.toString(), "Spec", "req_specification", "Specifications", null, false);

        assertEquals("ELK/_default/Spec", created.id());
        assertEquals("https://polarion.example.com/polarion/#/project/ELK/wiki/Spec", created.portalUrl());
        http.server.verify();
    }

    @Test
    void importDocumentWaitsUntilTheJobSucceeds(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("spec.docx");
        Files.write(file, new byte[] {0x50, 0x4b});
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/actions/importWordDocument"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                """
                                {"data":{"type":"jobs","id":"job-1","attributes":{"state":"RUNNING"}}}
                                """));
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/jobs/job-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"data":{"type":"jobs","id":"job-1","attributes":{"state":"RUNNING","status":{"type":"OK"}}}}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/jobs/job-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"data":{"type":"jobs","id":"job-1","attributes":{"state":"FINISHED","status":{"type":"OK"}}}}
                        """,
                        MediaType.APPLICATION_JSON));

        CreatedDocument created = (CreatedDocument) new DocumentTools(http.client)
                .importDocument("ELK", file.toString(), "Spec", "req_specification", "Specifications", null, false);

        assertEquals("https://polarion.example.com/polarion/#/project/ELK/wiki/Spec", created.portalUrl());
        http.server.verify();
    }

    @Test
    void importDocumentDoesNotReturnALinkWhenTheJobFails(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("spec.docx");
        Files.write(file, new byte[] {0x50, 0x4b});
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/actions/importWordDocument"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                """
                                {"data":{"type":"jobs","id":"job-1","attributes":{"state":"FINISHED","status":{"type":"ERROR","message":"bad docx"}}}}
                                """));

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> new DocumentTools(http.client)
                        .importDocument("ELK", file.toString(), "Spec", "req_specification", "Specifications", null, false));

        assertTrue(error.getMessage().contains("bad docx"), error.getMessage());
        http.server.verify();
    }

    @Test
    void createRequirementWorkItemCopiesPartHtmlAndLeavesTheLiveDoc() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts?")))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"type":"document_parts","id":"ELK/_default/Specs/heading_1","attributes":{"type":"heading","level":2,"headingText":"Button Press"}},
                          {"type":"document_parts","id":"ELK/_default/Specs/polarion_9","attributes":{"type":"normal","content":{"type":"text/html","value":"<table><tr><td>Keep</td></tr></table><p><b>formatted</b></p><img src=\\"diagram.png\\">"}}}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("<h2>Button Press</h2>"), body);
                    assertTrue(body.contains("<table><tr><td>Keep</td></tr></table>"), body);
                    assertTrue(body.contains("<b>formatted</b>"), body);
                    assertTrue(body.contains("diagram.png"), body);
                    assertTrue(body.contains("ELK/_default/Specs"), body);
                    assertFalse(body.contains("Global ID"), body);
                })
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"workitems","id":"ELK/ELK-9","links":{"portal":"https://example.test/wi"}}]}
                        """,
                        MediaType.APPLICATION_JSON));

        CreatedWorkItem created = (CreatedWorkItem) new DocumentTools(http.client)
                .createRequirementWorkItem(
                        "ELK",
                        "Specs",
                        "softwarerequirement",
                        "Button Press",
                        List.of("heading_1", "polarion_9"),
                        null,
                        false);

        assertEquals("ELK/ELK-9", created.id());
        assertEquals("https://example.test/wi", created.portalUrl());
        http.server.verify();
    }

    @Test
    void createRequirementWorkItemCopiesDocumentImagesOntoTheWorkItem() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, (byte) 0xff};
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts?")))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"type":"document_parts","id":"ELK/_default/Specs/polarion_3","attributes":{"type":"normal","content":{"type":"text/html","value":"<p>See</p><img src=\\"attachment:image1.png\\" style=\\"width: 10px;\\">"}}}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("attachment:image1.png"), body);
                })
                .andRespond(withSuccess(
                        """
                        {"data":[{"type":"workitems","id":"ELK/ELK-9","links":{"portal":"https://example.test/wi"}}]}
                        """,
                        MediaType.APPLICATION_JSON));
        http.server
                .expect(requestTo(PolarionHttp.ROOT
                        + "/projects/ELK/spaces/_default/documents/Specs/attachments/image1.png/content"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(png, MediaType.APPLICATION_OCTET_STREAM));
        http.server
                .expect(requestTo(PolarionHttp.ROOT + "/projects/ELK/workitems/ELK-9/attachments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                    String text = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
                    assertTrue(text.contains("image1.png"), text);
                    assertTrue(text.contains("workitem_attachments"), text);
                    int marker = indexOf(body, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, (byte) 0xff});
                    assertTrue(marker >= 0, text);
                })
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"data\":[{\"type\":\"workitem_attachments\",\"id\":\"ELK/ELK-9/image1.png\"}]}"));

        CreatedWorkItem created = (CreatedWorkItem) new DocumentTools(http.client)
                .createRequirementWorkItem(
                        "ELK", "Specs", "softwarerequirement", "Diagram", List.of("polarion_3"), null, false);

        assertEquals("ELK/ELK-9", created.id());
        http.server.verify();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void createRequirementWorkItemDryRunDoesNotCreate() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/_default/documents/Specs/parts?")))
                .andRespond(withSuccess(
                        """
                        {"data":[
                          {"type":"document_parts","id":"ELK/_default/Specs/polarion_1","attributes":{"type":"normal","content":{"type":"text/html","value":"<ul><li>One</li></ul>"}}}
                        ]}
                        """,
                        MediaType.APPLICATION_JSON));

        RequirementWorkItemPreview preview = (RequirementWorkItemPreview) new DocumentTools(http.client)
                .createRequirementWorkItem("ELK", "Specs", "requirement", "List", List.of("polarion_1"), null, null);

        assertTrue(preview.dryRun());
        assertEquals("<ul><li>One</li></ul>", preview.description());
        http.server.verify();
    }

    @Test
    void disallowedProjectIsRefused() {
        PolarionHttp http = PolarionHttp.open(ProjectAllowlist.parse("ELK"));

        RuntimeException error =
                assertThrows(RuntimeException.class, () -> new DocumentTools(http.client).listDocuments("OTHER", null, null, null));

        assertTrue(error.getMessage().contains("POLARION_PROJECT_ALLOWLIST"));
        http.server.verify();
    }

    @Test
    void rejectedTokenDoesNotEchoTheToken() {
        PolarionHttp http = PolarionHttp.open();
        http.server
                .expect(requestTo(Matchers.startsWith(PolarionHttp.ROOT + "/projects/ELK/spaces/")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"errors\":[{\"detail\":\"" + PolarionHttp.TOKEN + "\"}]}"));

        RuntimeException error =
                assertThrows(RuntimeException.class, () -> new DocumentTools(http.client).listDocuments("ELK", null, null, null));

        assertFalse(error.getMessage().contains(PolarionHttp.TOKEN));
    }
}
