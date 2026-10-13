package dev.arctic.polarion;

import java.util.stream.Stream;

import dev.arctic.polarion.testing.Fixtures;
import dev.arctic.polarion.testing.Replay;
import dev.arctic.polarion.testing.Scenario;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Replays shared link fixtures against {@link Links}. */
class LinkScenarioTest {

    static Stream<Named<Scenario>> scenarios() {
        return Fixtures.scenarios("links").stream()
                .map(scenario -> Named.of(scenario.toString(), scenario));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void replays(Scenario scenario) {
        ProjectAllowlistSource allowlist = () -> ProjectAllowlist.parse(
                scenario.env().get("POLARION_PROJECT_ALLOWLIST"));
        Replay.run(scenario, credentials -> invoke(
                new Links(new PolarionClient(PolarionClients.restClient(), credentials), allowlist),
                scenario));
    }

    private static Object invoke(Links links, Scenario scenario) {
        JsonNode args = scenario.args();
        return switch (scenario.operation()) {
            case "list_link_roles" -> links.listLinkRoles(stringArg(args, "project_id"));
            case "list_work_item_links" -> links.listWorkItemLinks(
                    stringArg(args, "project_id"), stringArg(args, "work_item_id"));
            case "list_work_item_backlinks" -> links.listWorkItemBacklinks(
                    stringArg(args, "project_id"), stringArg(args, "work_item_id"));
            case "create_work_item_link" -> links.createWorkItemLink(
                    stringArg(args, "project_id"),
                    stringArg(args, "work_item_id"),
                    stringArg(args, "target_work_item_id"),
                    stringArg(args, "role"),
                    stringArg(args, "target_project_id"),
                    boolArg(args, "dry_run", true));
            case "delete_work_item_link" -> links.deleteWorkItemLink(
                    stringArg(args, "project_id"),
                    stringArg(args, "work_item_id"),
                    stringArg(args, "target_work_item_id"),
                    stringArg(args, "role"),
                    stringArg(args, "target_project_id"),
                    boolArg(args, "dry_run", true));
            default -> throw new AssertionError("No Java mapping for operation " + scenario.operation());
        };
    }

    private static String stringArg(JsonNode args, String name) {
        JsonNode node = args.get(name);
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    private static Boolean boolArg(JsonNode args, String name, boolean defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaultValue;
        }
        return node.asBoolean();
    }
}
