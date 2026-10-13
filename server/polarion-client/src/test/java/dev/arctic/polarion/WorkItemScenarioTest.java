package dev.arctic.polarion;

import java.util.stream.Stream;

import dev.arctic.polarion.testing.Fixtures;
import dev.arctic.polarion.testing.Replay;
import dev.arctic.polarion.testing.Scenario;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Replays the shared work-item fixtures against {@link WorkItems}. */
class WorkItemScenarioTest {

    static Stream<Named<Scenario>> scenarios() {
        return Fixtures.scenarios("workitems").stream()
                .map(scenario -> Named.of(scenario.toString(), scenario));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void replays(Scenario scenario) {
        ProjectAllowlistSource allowlist =
                () -> ProjectAllowlist.parse(scenario.env().get("POLARION_PROJECT_ALLOWLIST"));
        Replay.run(scenario, credentials -> invoke(
                new WorkItems(new PolarionClient(PolarionClients.restClient(), credentials), allowlist),
                scenario));
    }

    private static Object invoke(WorkItems workItems, Scenario scenario) {
        JsonNode args = scenario.args();
        return switch (scenario.operation()) {
            case "list_work_items" -> workItems.listWorkItems(
                    arg(args, "project_id"),
                    optional(args, "query"),
                    optionalInt(args, "page_size"),
                    optionalInt(args, "page_number"));
            case "get_work_item" -> workItems.getWorkItem(arg(args, "project_id"), arg(args, "work_item_id"));
            case "create_work_item" -> workItems.createWorkItem(
                    arg(args, "project_id"),
                    arg(args, "wi_type"),
                    arg(args, "title"),
                    optional(args, "description"),
                    optional(args, "module"),
                    optional(args, "status"),
                    optional(args, "severity"),
                    optionalBoolean(args, "dry_run"));
            case "update_work_item" -> workItems.updateWorkItem(
                    arg(args, "project_id"),
                    arg(args, "work_item_id"),
                    optional(args, "title"),
                    optional(args, "description"),
                    optional(args, "status"),
                    optional(args, "change_type_to"),
                    optionalBoolean(args, "dry_run"));
            case "list_work_item_types" -> workItems.listWorkItemTypes(arg(args, "project_id"));
            case "create_work_item_type" -> workItems.createWorkItemType(
                    arg(args, "project_id"),
                    arg(args, "type_id"),
                    arg(args, "name"),
                    optional(args, "description"),
                    optional(args, "color"),
                    optional(args, "icon_url"),
                    optionalBoolean(args, "dry_run"));
            default -> throw new AssertionError("No Java mapping for operation " + scenario.operation());
        };
    }

    private static String arg(JsonNode args, String name) {
        return args.path(name).asString();
    }

    private static String optional(JsonNode args, String name) {
        return args.has(name) && !args.get(name).isNull() ? args.get(name).asString() : null;
    }

    private static Boolean optionalBoolean(JsonNode args, String name) {
        return args.has(name) && !args.get(name).isNull() ? args.get(name).asBoolean() : null;
    }

    private static Integer optionalInt(JsonNode args, String name) {
        return args.has(name) && !args.get(name).isNull() ? args.get(name).asInt() : null;
    }
}
