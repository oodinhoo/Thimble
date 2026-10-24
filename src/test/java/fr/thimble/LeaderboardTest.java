package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Le tri du classement est la seule logique qui tourne sans serveur. Il attrape les erreurs
 * d'ordre et de depart (victoires d'abord, des a coudre pour departager), invisibles en jeu.
 */
class LeaderboardTest {

    private static Leaderboard.Stats stats(String name, int wins, int thimbles) {
        Leaderboard.Stats s = new Leaderboard.Stats(UUID.randomUUID(), name);
        s.wins = wins;
        s.thimbles = thimbles;
        return s;
    }

    @Test
    void victoiresDabordPuisDesACoudre() {
        List<Leaderboard.Stats> top = Leaderboard.top(List.of(
                stats("Peu", 1, 99),
                stats("MoinsDeDac", 5, 0),
                stats("PlusDeDac", 5, 10)), 10);
        assertEquals(List.of("PlusDeDac", "MoinsDeDac", "Peu"), top.stream().map(s -> s.name).toList(),
                "5 victoires passent avant 1, et a egalite de victoires c'est les des a coudre qui tranchent");
    }

    @Test
    void tailleBorneLeTop() {
        List<Leaderboard.Stats> top = Leaderboard.top(List.of(
                stats("A", 3, 0), stats("B", 2, 0), stats("C", 1, 0)), 2);
        assertEquals(List.of("A", "B"), top.stream().map(s -> s.name).toList());
        assertEquals(0, Leaderboard.top(List.of(stats("A", 1, 0)), 0).size(),
                "une taille nulle ou negative ne doit pas casser le tri");
    }
}
