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
    public ToolCallbackProvider polarionTools(WhoamiTool whoami) {
        return MethodToolCallbackProvider.builder().toolObjects(whoami).build();
    }
}
