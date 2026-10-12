package dev.arctic.local.config;

import java.util.Optional;

/** Stores secrets by target name, outside {@code config.json}. */
public interface SecretStore {

    String POLARION_TOKEN = "Arctic/polarion-token";
    String MODEL_KEY = "Arctic/model-key";

    Optional<String> read(String target);

    void write(String target, String secret);

    /** Does nothing if the target does not exist. */
    void delete(String target);
}
