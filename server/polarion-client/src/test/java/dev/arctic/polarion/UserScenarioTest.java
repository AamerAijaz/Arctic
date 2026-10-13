package dev.arctic.polarion;

import java.util.List;
import java.util.stream.Stream;

import dev.arctic.polarion.testing.Fixtures;
import dev.arctic.polarion.testing.Replay;
import dev.arctic.polarion.testing.Scenario;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Replays the shared {@code users} fixtures against {@link Users}. */
class UserScenarioTest {

    static Stream<Named<Scenario>> scenarios() {
        return Fixtures.scenarios("users").stream()
                .map(scenario -> Named.of(scenario.toString(), scenario));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void replays(Scenario scenario) {
        ProjectAllowlist allowlist = ProjectAllowlist.parse(scenario.env().get("POLARION_PROJECT_ALLOWLIST"));
        Replay.run(scenario, credentials -> invoke(
                new Users(new PolarionClient(PolarionClients.restClient(), credentials), () -> allowlist),
                scenario));
    }

    private static Object invoke(Users users, Scenario scenario) {
        JsonNode args = scenario.args();
        return switch (scenario.operation()) {
            case "list_project_users" -> users.listProjectUsers(
                    args.path("project_id").asString(),
                    args.has("role") ? args.path("role").asString() : "project_assignable",
                    args.has("page_size") ? args.path("page_size").asInt() : 100,
                    args.has("page_number") ? args.path("page_number").asInt() : 1);
            case "assign_work_item" -> users.assignWorkItem(
                    args.path("project_id").asString(),
                    args.path("work_item_id").asString(),
                    userIds(args),
                    args.has("dry_run") && !args.path("dry_run").isNull()
                            ? args.path("dry_run").asBoolean()
                            : null);
            default -> throw new AssertionError("No Java mapping for operation " + scenario.operation());
        };
    }

    private static List<String> userIds(JsonNode args) {
        return args.path("user_ids").valueStream().map(JsonNode::asString).toList();
    }
}
