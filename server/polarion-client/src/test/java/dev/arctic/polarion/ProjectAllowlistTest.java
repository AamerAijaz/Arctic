package dev.arctic.polarion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class ProjectAllowlistTest {

    @Test
    void emptyAllowsEverything() {
        assertTrue(ProjectAllowlist.ALL.allows("ANY"));
        assertTrue(ProjectAllowlist.parse("  ").allows("ANY"));
        assertTrue(ProjectAllowlist.parse(null).allows("ANY"));
        assertTrue(ProjectAllowlist.parse(" , ,").allows("ANY"));
    }

    @Test
    void parseTrimsAndDropsBlankEntries() {
        assertEquals(Set.of("ELK", "MOOSE"), ProjectAllowlist.parse(" ELK, ,MOOSE ,").projects());
    }

    @Test
    void checkRejectsOtherProjectsWith403() {
        var allowlist = new ProjectAllowlist(List.of("ELK"));
        assertDoesNotThrow(() -> allowlist.check("ELK"));
        var error = assertThrows(PolarionApiException.class, () -> allowlist.check("elk"));
        assertEquals(403, error.status());
        assertEquals("Polarion request failed (403): Project elk is not in POLARION_PROJECT_ALLOWLIST.",
                error.getMessage());
    }

    @Test
    void filterKeepsAllowedItems() {
        var allowlist = new ProjectAllowlist(List.of("ELK"));
        assertEquals(List.of("ELK"), allowlist.filter(List.of("ELK", "MOOSE"), Function.identity()));
        assertFalse(allowlist.allows("MOOSE"));
    }
}
