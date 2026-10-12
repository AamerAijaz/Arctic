package dev.arctic.polarion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

class PolarionAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PolarionAutoConfiguration.class));

    @Test
    void createsClientWhenCredentialsExist() {
        runner.withUserConfiguration(WithCredentials.class).run(context -> {
            assertTrue(context.containsBean("polarionClient"));
            assertEquals(1, context.getBeansOfType(PolarionClient.class).size());
            assertEquals(0, context.getBeansOfType(RestClient.class).size());
        });
    }

    @Test
    void skipsClientWithoutCredentials() {
        runner.run(context -> {
            assertFalse(context.containsBean("polarionClient"));
            assertEquals(0, context.getBeansOfType(PolarionClient.class).size());
        });
    }

    @Configuration
    static class WithCredentials {

        @Bean
        CredentialSource polarionCredentials() {
            return () -> new PolarionCredentials("https://example.invalid/polarion/rest/v1", "tok");
        }
    }
}
