package fr.thimble;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
  * The display logic is the only one that runs without a server: it touches neither the world
  * nor the players. This test catches counting errors, which only show in game.
 */
class HudTest {

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void fullAndDimmedHearts() {
        assertEquals("♥♥♥".replace('♥', '\u2764'), plain(Hud.hearts(3, 3)));
        assertEquals("♥♥♥".replace('♥', '\u2764'), plain(Hud.hearts(1, 3)), "a lost heart stays shown, dimmed");
        assertEquals("", plain(Hud.hearts(0, 0)));
    }

    @Test
    void gaugeAlwaysHasTenSteps() {
        assertEquals(10, plain(Hud.gauge(27, 49)).length());
        assertEquals(10, plain(Hud.gauge(0, 49)).length());
        assertEquals(10, plain(Hud.gauge(49, 49)).length());
        assertEquals(10, plain(Hud.gauge(1, 0)).length(), "a pool without blocks must not break the gauge");
    }

    @Test
    void colorSquareRecognizesDyedBlocks() {
        assertEquals("\u25a0", plain(Hud.square(Material.RED_WOOL)));
        assertEquals("\u25a0", plain(Hud.square(Material.LIGHT_BLUE_CONCRETE)));
        assertEquals("\u25a0", plain(Hud.square(Material.STONE)), "an undyed block can still be shown");
        assertEquals("\u25a0", plain(Hud.square(null)));
    }
}
