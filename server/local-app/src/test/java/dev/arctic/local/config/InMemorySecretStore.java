package dev.arctic.local.config;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class InMemorySecretStore implements SecretStore {

    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public Optional<String> read(String target) {
        return Optional.ofNullable(secrets.get(target));
    }

    @Override
    public void write(String target, String secret) {
        secrets.put(target, secret);
    }

    @Override
    public void delete(String target) {
        secrets.remove(target);
    }
}
