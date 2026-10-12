package dev.arctic.polarion;

import java.util.stream.Stream;

import dev.arctic.polarion.testing.Fixtures;
import dev.arctic.polarion.testing.Replay;
import dev.arctic.polarion.testing.Scenario;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Replays the shared fixtures. Areas are added here as their operations are ported. */
class PolarionScenarioTest {

    static Stream<Named<Scenario>> scenarios() {
        return Stream.of("auth")
                .flatMap(area -> Fixtures.scenarios(area).stream())
                .map(scenario -> Named.of(scenario.toString(), scenario));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void replays(Scenario scenario) {
        Replay.run(scenario, credentials -> invoke(
                new PolarionClient(PolarionClients.restClient(), credentials), scenario));
    }

    private static Object invoke(PolarionClient client, Scenario scenario) {
        return switch (scenario.operation()) {
            case "get_current_user" -> client.getCurrentUser();
            default -> throw new AssertionError("No Java mapping for operation " + scenario.operation());
        };
    }
}
