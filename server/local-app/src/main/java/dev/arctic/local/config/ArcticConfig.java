package dev.arctic.local.config;

import java.util.List;

/** The non-secret settings in {@code config.json}. Secrets live in a {@link SecretStore}. */
public record ArcticConfig(String polarionUrl, List<String> projectAllowlist, Model model) {

    public ArcticConfig {
        projectAllowlist = projectAllowlist == null ? List.of() : List.copyOf(projectAllowlist);
    }

    /** An OpenAI-compatible chat model endpoint. */
    public record Model(String baseUrl, String model) {
    }
}
