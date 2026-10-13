package dev.arctic.polarion;

import java.util.stream.Stream;

import dev.arctic.polarion.testing.Fixtures;
import dev.arctic.polarion.testing.Replay;
import dev.arctic.polarion.testing.Scenario;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Replays shared project fixtures against {@link Projects}. */
class ProjectScenarioTest {

    static Stream<Named<Scenario>> scenarios() {
        return Fixtures.scenarios("projects").stream()
                .map(scenario -> Named.of(scenario.toString(), scenario));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void replays(Scenario scenario) {
        ProjectAllowlistSource allowlist = () -> ProjectAllowlist.parse(
                scenario.env().get("POLARION_PROJECT_ALLOWLIST"));
        Replay.run(scenario, credentials -> invoke(
                new Projects(new PolarionClient(PolarionClients.restClient(), credentials), allowlist),
                scenario));
    }

    private static Object invoke(Projects projects, Scenario scenario) {
        JsonNode args = scenario.args();
        return switch (scenario.operation()) {
            case "list_projects" -> projects.listProjects(
                    intArg(args, "page_size", 100),
                    intArg(args, "page_number", 1),
                    stringArg(args, "query"));
            case "get_project" -> projects.getProject(stringArg(args, "project_id"));
            default -> throw new AssertionError("No Java mapping for operation " + scenario.operation());
        };
    }

    private static int intArg(JsonNode args, String name, int defaultValue) {
        JsonNode node = args.get(name);
        return node == null || node.isNull() || node.isMissingNode() ? defaultValue : node.asInt();
    }

    private static String stringArg(JsonNode args, String name) {
        JsonNode node = args.get(name);
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
    }
}
