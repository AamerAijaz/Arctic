package dev.arctic.polarion.testing;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** One recorded scenario file: the operation to call, the HTTP exchanges it must make, and the expected outcome. */
public record Scenario(
        String area,
        String name,
        String operation,
        JsonNode args,
        Map<String, String> env,
        List<Exchange> exchanges,
        JsonNode expect) {

    /** One request the client must send and the response to return. */
    public record Exchange(JsonNode request, JsonNode response) {
    }

    static Scenario load(Path file) {
        JsonNode root = Fixtures.read(file);
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".json".length());
        if (!name.equals(root.path("name").asString())) {
            throw new IllegalStateException(file + ": name does not match the file name");
        }
        Map<String, String> env = new LinkedHashMap<>();
        root.path("env").properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asString()));
        List<Exchange> exchanges = root.path("exchanges").valueStream()
                .map(exchange -> new Exchange(exchange.path("request"), exchange.path("response")))
                .toList();
        return new Scenario(
                file.getParent().getFileName().toString(),
                name,
                root.path("call").path("operation").asString(),
                root.path("call").path("args"),
                Map.copyOf(env),
                exchanges,
                root.path("expect"));
    }

    @Override
    public String toString() {
        return area + "/" + name;
    }
}
