package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * La logique d'affichage est la seule qui tourne sans serveur : elle ne touche ni au monde,
 * ni aux joueurs. Ce test attrape les erreurs de comptage, qui ne se voient qu'en jeu.
 */
class HudTest {

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void coeursPleinsEtEteints() {
        assertEquals("♥♥♥".replace('♥', '\u2764'), plain(Hud.hearts(3, 3)));
        assertEquals("♥♥♥".replace('♥', '\u2764'), plain(Hud.hearts(1, 3)), "un coeur perdu reste affiche, eteint");
        assertEquals("", plain(Hud.hearts(0, 0)));
    }

    @Test
    void jaugeToujoursEnDixCrans() {
        assertEquals(10, plain(Hud.gauge(27, 49)).length());
        assertEquals(10, plain(Hud.gauge(0, 49)).length());
        assertEquals(10, plain(Hud.gauge(49, 49)).length());
        assertEquals(10, plain(Hud.gauge(1, 0)).length(), "une piscine vide de cases ne doit pas casser la jauge");
    }

    @Test
    void carreDeCouleurReconnaitLesBlocsTeints() {
        assertEquals("\u25a0", plain(Hud.square(Material.RED_WOOL)));
        assertEquals("\u25a0", plain(Hud.square(Material.LIGHT_BLUE_CONCRETE)));
        assertEquals("\u25a0", plain(Hud.square(Material.STONE)), "un bloc non teint reste affichable");
        assertEquals("\u25a0", plain(Hud.square(null)));
    }
}
