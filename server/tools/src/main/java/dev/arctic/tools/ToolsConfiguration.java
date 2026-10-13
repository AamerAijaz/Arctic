package dev.arctic.tools;

import dev.arctic.polarion.PolarionClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the Polarion tools. Needs a {@link PolarionClient} bean. */
@Configuration(proxyBeanMethods = false)
public class ToolsConfiguration {

    @Bean
    public WhoamiTool whoamiTool(PolarionClient polarion) {
        return new WhoamiTool(polarion);
    }

    @Bean
    public PolarionServerTool polarionServerTool(PolarionClient polarion) {
        return new PolarionServerTool(polarion);
    }

    @Bean
    public ProjectTool projectTool(dev.arctic.polarion.Projects projects) {
        return new ProjectTool(projects);
    }

    @Bean
    public WorkItemTool workItemTool(dev.arctic.polarion.WorkItems workItems) {
        return new WorkItemTool(workItems);
    }

    @Bean
    public LinkTool linkTool(dev.arctic.polarion.Links links) {
        return new LinkTool(links);
    }

    @Bean
    public UserTool userTool(PolarionClient polarion, dev.arctic.polarion.ProjectAllowlistSource allowlist) {
        return new UserTool(polarion, allowlist);
    }

    @Bean
    public ToolCallbackProvider polarionTools(
            WhoamiTool whoami,
            PolarionServerTool polarionServer,
            ProjectTool projects,
            WorkItemTool workItems,
            LinkTool links,
            UserTool users) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(whoami, polarionServer, projects, workItems, links, users)
                .build();
    }
}
