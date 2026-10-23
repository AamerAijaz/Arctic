package com.arctic.mcp;

import com.arctic.mcp.polarion.EnvironmentPolarionCredentialsSource;
import com.arctic.mcp.polarion.PolarionClient;
import com.arctic.mcp.polarion.PolarionCredentialsSource;
import com.arctic.mcp.tool.DocumentTools;
import com.arctic.mcp.tool.LinkTools;
import com.arctic.mcp.tool.ProjectTools;
import com.arctic.mcp.tool.TestStepTools;
import com.arctic.mcp.tool.UserTools;
import com.arctic.mcp.tool.WhoamiTool;
import com.arctic.mcp.tool.WorkItemTools;
import java.nio.file.Path;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wires Polarion Credentials into the Polarion Client and registers Tools. */
@Configuration
public class PolarionConfiguration {

  @Bean
  PolarionCredentialsSource polarionCredentialsSource() {
    return new EnvironmentPolarionCredentialsSource(
        System.getenv(), Path.of(System.getProperty("user.dir")));
  }

  @Bean
  RestClient polarionRestClient() {
    return RestClient.builder().build();
  }

  @Bean
  PolarionClient polarionClient(
      PolarionCredentialsSource credentials, RestClient polarionRestClient) {
    return new PolarionClient(credentials, polarionRestClient);
  }

  @Bean
  WhoamiTool whoamiTool(PolarionClient client) {
    return new WhoamiTool(client);
  }

  @Bean
  ProjectTools projectTools(PolarionClient client) {
    return new ProjectTools(client);
  }

  @Bean
  WorkItemTools workItemTools(PolarionClient client) {
    return new WorkItemTools(client);
  }

  @Bean
  LinkTools linkTools(PolarionClient client) {
    return new LinkTools(client);
  }

  @Bean
  DocumentTools documentTools(PolarionClient client) {
    return new DocumentTools(client);
  }

  @Bean
  UserTools userTools(PolarionClient client) {
    return new UserTools(client);
  }

  @Bean
  TestStepTools testStepTools(PolarionClient client) {
    return new TestStepTools(client);
  }

  @Bean
  public ToolCallbackProvider arcticTools(
      WhoamiTool whoami,
      ProjectTools projects,
      WorkItemTools workItems,
      LinkTools links,
      DocumentTools documents,
      UserTools users,
      TestStepTools testSteps) {
    return new ToolCallLogging(
        MethodToolCallbackProvider.builder()
            .toolObjects(whoami, projects, workItems, links, documents, users, testSteps)
            .build());
  }
}
