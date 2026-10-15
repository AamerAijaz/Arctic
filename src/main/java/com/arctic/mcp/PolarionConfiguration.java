package com.arctic.mcp;

import com.arctic.mcp.polarion.EnvironmentPolarionCredentialsSource;
import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionCredentialsSource;
import com.arctic.mcp.tool.WhoamiTool;
import java.nio.file.Path;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wires Polarion Credentials into the Polarion Client and registers whoami. */
@Configuration
public class PolarionConfiguration {

    @Bean
    PolarionCredentialsSource polarionCredentialsSource() {
        return new EnvironmentPolarionCredentialsSource(System.getenv(), Path.of(System.getProperty("user.dir")));
    }

    @Bean
    RestClient polarionRestClient() {
        return RestClient.builder().build();
    }

    @Bean
    PolarionClient polarionClient(PolarionCredentialsSource credentials, RestClient polarionRestClient) {
        return new PolarionClient(credentials, polarionRestClient);
    }

    @Bean
    WhoamiTool whoamiTool(PolarionClient client) {
        return new WhoamiTool(client);
    }

    @Bean
    public ToolCallbackProvider arcticTools(WhoamiTool whoami) {
        return new ToolCallLogging(MethodToolCallbackProvider.builder().toolObjects(whoami).build());
    }
}
