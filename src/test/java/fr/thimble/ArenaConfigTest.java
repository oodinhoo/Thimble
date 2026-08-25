package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Configuration d'arene : la partie qui ne touche ni au monde ni aux joueurs, donc testable
 * sans serveur. L'arene est construite sans plugin ; si ces methodes se mettaient a en avoir
 * besoin, le test le signalerait, et ce serait une information utile.
 */
class ArenaConfigTest {

    @Test
    void lesReglagesSontBornes() {
        Arena arena = new Arena(null, "test");

        arena.setValue("min", 0);
        arena.setValue("max", 1);
        arena.setValue("lives", -3);
        arena.setValue("timeout", 0);

        assertEquals(2, arena.minPlayers(), "une partie a moins de deux n'a pas de dernier survivant");
        assertEquals(2, arena.maxPlayers());
        assertEquals(1, arena.lives(), "zero vie eliminerait tout le monde au premier saut");
        assertEquals(3, arena.timeout(), "un chrono nul ratait le tour avant meme le saut");
    }

    @Test
    void lesValeursValidesPassent() {
        Arena arena = new Arena(null, "test");

        assertTrue(arena.setValue("min", 6));
        assertEquals(6, arena.minPlayers());
        assertFalse(arena.setValue("inconnu", 5), "une cle inconnue doit etre signalee, pas ignoree");
    }

    @Test
    void uneAreneVideEnumereCeQuiLuiManque() {
        List<String> missing = new Arena(null, "test").missing();

        assertEquals(4, missing.size());
        assertTrue(missing.contains("setwait"));
        assertTrue(missing.contains("setjump"));
        assertTrue(missing.contains("setspectator"));
        assertTrue(missing.stream().anyMatch(item -> item.startsWith("pos1/pos2")));
    }

    @Test
    void forcerLeDemarrageDUneAreneVideEchoue() {
        assertFalse(new Arena(null, "test").forceStart(),
                "sans joueur il n'y a rien a lancer, et la commande ne doit pas annoncer le contraire");
    }

    @Test
    void identifiantDArene() {
        assertTrue(ThimblePlugin.validId("piscine1"));
        assertTrue(ThimblePlugin.validId("piscine_1-bis"));
        assertFalse(ThimblePlugin.validId("piscine.1"), "Bukkit lit le point comme un separateur de chemin");
        assertFalse(ThimblePlugin.validId("piscine 1"));
        assertFalse(ThimblePlugin.validId(""));
        assertFalse(ThimblePlugin.validId("a".repeat(33)));
    }
}
