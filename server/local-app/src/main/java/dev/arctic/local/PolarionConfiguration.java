package dev.arctic.local;

import dev.arctic.local.config.ArcticCredentialSource;
import dev.arctic.local.config.ConfigStore;
import dev.arctic.local.config.DataDirectory;
import dev.arctic.local.config.SecretStore;
import dev.arctic.local.config.WindowsCredentialStore;
import dev.arctic.polarion.CredentialSource;
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
}
