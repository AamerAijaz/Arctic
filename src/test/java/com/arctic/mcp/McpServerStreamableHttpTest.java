package com.arctic.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.arctic.mcp.polarion.PolarionCredentials;
import com.arctic.mcp.polarion.PolarionCredentialsSource;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = ArcticMcpApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class McpServerStreamableHttpTest {

    private static final String TOKEN = "super-secret-token";
    private static final RestClient.Builder HTTP = RestClient.builder();
    private static final MockRestServiceServer POLARION = MockRestServiceServer.bindTo(HTTP).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ServletWebServerApplicationContext serverContext;

    @TestBean(name = "polarionCredentialsSource", methodName = "credentials")
    @SuppressWarnings("unused")
    private PolarionCredentialsSource polarionCredentialsSource;

    @TestBean(name = "polarionRestClient", methodName = "polarionHttp")
    @SuppressWarnings("unused")
    private RestClient polarionRestClient;

    @Test
    void hostListsWhoamiAndCallsItOnLoopback() {
        POLARION.reset();
        POLARION.expect(requestTo("https://polarion.example.com/polarion/rest/v1/user"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"data":{"type":"users","id":"alice","attributes":{"name":"Alice","email":"alice@example.com"}}}
                        """,
                        MediaType.APPLICATION_JSON));

        InetAddress listenAddress = listenAddress();
        assertNotNull(listenAddress);
        assertTrue(listenAddress.isLoopbackAddress());

        try (McpSyncClient client = McpClient.sync(
                        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                                .endpoint("/mcp")
                                .build())
                .build()) {
            client.initialize();
            ListToolsResult listed = client.listTools();
            assertEquals(
                    Set.of(
                            "whoami",
                            "list_projects",
                            "get_project",
                            "list_work_items",
                            "get_work_item",
                            "create_work_item",
                            "update_work_item",
                            "list_work_item_types",
                            "create_work_item_type",
                            "list_link_roles",
                            "list_work_item_links",
                            "list_work_item_backlinks",
                            "create_work_item_link",
                            "delete_work_item_link",
                            "list_documents",
                            "get_document",
                            "create_document",
                            "import_document",
                            "list_document_parts",
                            "move_document_part",
                            "delete_document_parts",
                            "create_document_work_item",
                            "create_requirement_work_item",
                            "list_project_users",
                            "assign_work_item"),
                    listed.tools().stream().map(Tool::name).collect(Collectors.toSet()));
            Tool whoami = listed.tools().stream()
                    .filter(tool -> "whoami".equals(tool.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("whoami was not listed: " + listed));
            assertFalse(whoami.inputSchema().toString().toLowerCase().contains("token"));

            CallToolResult result = client.callTool(
                    CallToolRequest.builder("whoami").arguments(Map.of()).build());
            assertFalse(Boolean.TRUE.equals(result.isError()));
            String payload = result.toString();
            assertTrue(payload.contains("\"id\":\"alice\"") || payload.contains("\"id\": \"alice\""), payload);
            assertTrue(payload.contains("Alice"), payload);
            assertTrue(payload.contains("alice@example.com"), payload);
            assertFalse(payload.contains(TOKEN), payload);
        }

        POLARION.verify();
    }

    static PolarionCredentialsSource credentials() {
        return () -> new PolarionCredentials("https://polarion.example.com/polarion/rest/v1", TOKEN);
    }

    static RestClient polarionHttp() {
        return HTTP.build();
    }

    private InetAddress listenAddress() {
        TomcatWebServer tomcat = (TomcatWebServer) serverContext.getWebServer();
        Connector connector = tomcat.getTomcat().getConnector();
        AbstractProtocol<?> protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
        return protocol.getAddress();
    }
}
