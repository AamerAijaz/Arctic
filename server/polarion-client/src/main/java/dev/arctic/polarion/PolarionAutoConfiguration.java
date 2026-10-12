package dev.arctic.polarion;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnBean(CredentialSource.class)
@ConditionalOnMissingBean(PolarionClient.class)
public class PolarionAutoConfiguration {

    @Bean
    PolarionClient polarionClient(CredentialSource credentials) {
        return new PolarionClient(PolarionClients.restClient(), credentials);
    }
}
