package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
  * Arena configuration: the part that touches neither the world nor the players, so testable
  * without a server. The arena is built without a plugin; if these methods started to need
  * one, the test would report it, and that would be useful information.
 */
class ArenaConfigTest {

    @Test
    void settingsAreClamped() {
        Arena arena = new Arena(null, "test");

        arena.setValue("min", 0);
        arena.setValue("max", 1);
        arena.setValue("lives", -3);
        arena.setValue("timeout", 0);

        assertEquals(2, arena.minPlayers(), "a game with fewer than two players has no last survivor");
        assertEquals(2, arena.maxPlayers());
        assertEquals(1, arena.lives(), "zero lives would eliminate everyone on the first jump");
        assertEquals(3, arena.timeout(), "a zero timer missed the turn before the jump even started");
    }

    @Test
    void validValuesAreAccepted() {
        Arena arena = new Arena(null, "test");

        assertTrue(arena.setValue("min", 6));
        assertEquals(6, arena.minPlayers());
        assertFalse(arena.setValue("unknown", 5), "an unknown key must be reported, not ignored");
    }

    @Test
    void emptyArenaListsWhatIsMissing() {
        List<String> missing = new Arena(null, "test").missing();

        assertEquals(4, missing.size());
        assertTrue(missing.contains("setwait"));
        assertTrue(missing.contains("setjump"));
        assertTrue(missing.contains("setspectator"));
        assertTrue(missing.stream().anyMatch(item -> item.startsWith("pos1/pos2")));
    }

    @Test
    void forceStartingAnEmptyArenaFails() {
        assertFalse(new Arena(null, "test").forceStart(),
                "without players there is nothing to start, and the command must not claim otherwise");
    }

    @Test
    void arenaId() {
        assertTrue(ThimblePlugin.validId("pool1"));
        assertTrue(ThimblePlugin.validId("pool_1-bis"));
        assertFalse(ThimblePlugin.validId("pool.1"), "Bukkit reads the dot as a path separator");
        assertFalse(ThimblePlugin.validId("pool 1"));
        assertFalse(ThimblePlugin.validId(""));
        assertFalse(ThimblePlugin.validId("a".repeat(33)));
    }
}
