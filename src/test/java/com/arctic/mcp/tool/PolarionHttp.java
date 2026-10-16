package com.arctic.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionCredentials;
import com.arctic.mcp.polarion.PolarionCredentialsSource;
import com.arctic.mcp.polarion.ProjectAllowlist;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

final class PolarionHttp {

    static final String TOKEN = "super-secret-token";
    static final String ROOT = "https://polarion.example.com/polarion/rest/v1";

    final MockRestServiceServer server;
    final PolarionClient client;

    private PolarionHttp(MockRestServiceServer server, PolarionClient client) {
        this.server = server;
        this.client = client;
    }

    static PolarionHttp open() {
        return open(ProjectAllowlist.unrestricted());
    }

    static PolarionHttp open(ProjectAllowlist allowlist) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolarionCredentialsSource source = () -> new PolarionCredentials(ROOT, TOKEN);
        return new PolarionHttp(server, new PolarionClient(source, builder.build(), allowlist));
    }

    static RequestMatcher query(String name, String value) {
        return (ClientHttpRequest request) -> {
            String raw = request.getURI().getRawQuery();
            Map<String, String> params = new LinkedHashMap<>();
            if (raw != null) {
                for (String pair : raw.split("&")) {
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String decoded = parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    params.put(key, decoded);
                }
            }
            assertEquals(value, params.get(name), raw);
        };
    }
}
