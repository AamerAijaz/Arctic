package dev.arctic.local;

import dev.arctic.local.config.ArcticConfig;
import dev.arctic.local.config.ArcticCredentialSource;
import dev.arctic.local.config.ConfigStore;
import dev.arctic.local.config.DataDirectory;
import dev.arctic.local.config.SecretStore;
import dev.arctic.local.config.WindowsCredentialStore;
import dev.arctic.polarion.CredentialSource;
import dev.arctic.polarion.Links;
import dev.arctic.polarion.PolarionClient;
import dev.arctic.polarion.ProjectAllowlist;
import dev.arctic.polarion.ProjectAllowlistSource;
import dev.arctic.polarion.Projects;
import dev.arctic.polarion.Users;
import dev.arctic.polarion.WorkItems;
import dev.arctic.tools.ToolsConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(ToolsConfiguration.class)
class PolarionConfiguration {

    @Bean
    ConfigStore configStore() {
        return new ConfigStore(DataDirectory.resolve(System.getenv()));
    }

    @Bean
    SecretStore secretStore() {
        return new WindowsCredentialStore();
    }

    @Bean
    CredentialSource polarionCredentials(ConfigStore config, SecretStore secrets) {
        return new ArcticCredentialSource(System.getenv(), config, secrets);
    }

    @Bean
    ProjectAllowlistSource projectAllowlist(ConfigStore config) {
        return () -> new ProjectAllowlist(config.read().map(ArcticConfig::projectAllowlist).orElse(java.util.List.of()));
    }

    @Bean
    Projects projects(PolarionClient polarion, ProjectAllowlistSource allowlist) {
        return new Projects(polarion, allowlist);
    }

    @Bean
    WorkItems workItems(PolarionClient polarion, ProjectAllowlistSource allowlist) {
        return new WorkItems(polarion, allowlist);
    }

    @Bean
    Links links(PolarionClient polarion, ProjectAllowlistSource allowlist) {
        return new Links(polarion, allowlist);
    }

    @Bean
    Users users(PolarionClient polarion, ProjectAllowlistSource allowlist) {
        return new Users(polarion, allowlist);
    }
}
