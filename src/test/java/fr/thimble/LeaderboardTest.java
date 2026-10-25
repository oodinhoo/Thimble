package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
  * The leaderboard sort is the only logic that runs without a server. It catches ordering
  * and tie-break errors (wins first, thimbles to break ties), invisible in game.
 */
class LeaderboardTest {

    private static Leaderboard.Stats stats(String name, int wins, int thimbles) {
        Leaderboard.Stats s = new Leaderboard.Stats(UUID.randomUUID(), name);
        s.wins = wins;
        s.thimbles = thimbles;
        return s;
    }

    @Test
    void winsFirstThenThimbles() {
        List<Leaderboard.Stats> top = Leaderboard.top(List.of(
                stats("Few", 1, 99),
                stats("FewerThimbles", 5, 0),
                stats("MoreThimbles", 5, 10)), 10);
        assertEquals(List.of("MoreThimbles", "FewerThimbles", "Few"), top.stream().map(s -> s.name).toList(),
                "5 wins rank above 1, and with equal wins the thimbles break the tie");
    }

    @Test
    void sizeLimitsTheTop() {
        List<Leaderboard.Stats> top = Leaderboard.top(List.of(
                stats("A", 3, 0), stats("B", 2, 0), stats("C", 1, 0)), 2);
        assertEquals(List.of("A", "B"), top.stream().map(s -> s.name).toList());
        assertEquals(0, Leaderboard.top(List.of(stats("A", 1, 0)), 0).size(),
                "a zero or negative size must not break the sort");
    }
}
