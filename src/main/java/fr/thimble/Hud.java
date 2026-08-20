package fr.thimble;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * Habillage : titres, sons, barre de boss, tableau de score, particules.
 * Chaque element est desactivable dans config.yml (section {@code ui}).
 */
final class Hud {

    private static final Title.Times TIMES =
            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1200), Duration.ofMillis(400));

    private final ThimblePlugin plugin;
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Scoreboard> boards = new HashMap<>();

    Hud(ThimblePlugin plugin) {
        this.plugin = plugin;
    }

    void title(Player player, String key, String subKey, String... placeholders) {
        TagResolver[] resolvers = new TagResolver[placeholders.length / 2];
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolvers[i / 2] = Placeholder.unparsed(placeholders[i], placeholders[i + 1]);
        }
        titleResolved(player, key, subKey, resolvers);
    }

    void titleResolved(Player player, String key, String subKey, TagResolver... resolvers) {
        if (!plugin.ui("titles")) {
            return;
        }
        Component subtitle = subKey == null ? Component.empty() : plugin.messageResolved(subKey, resolvers);
        player.showTitle(Title.title(plugin.messageResolved(key, resolvers), subtitle, TIMES));
    }

    /** Consigne du moment, au-dessus de la barre d'objets. Reenvoyee chaque seconde, sinon elle s'efface. */
    void actionBar(Collection<Player> players, String key, String... placeholders) {
        if (!plugin.ui("actionbar")) {
            return;
        }
        Component text = plugin.message(key, placeholders);
        players.forEach(player -> player.sendActionBar(text));
    }

    /** Le carre de couleur d'un joueur, deduit du bloc qui lui est attribue. */
    static Component square(Material block) {
        for (DyeColor dye : DyeColor.values()) {
            if (block != null && block.name().startsWith(dye.name() + "_")) {
                Color color = dye.getColor();
                return Component.text("■",
                        TextColor.color(color.getRed(), color.getGreen(), color.getBlue()));
            }
        }
        return Component.text("■", NamedTextColor.WHITE);
    }

    /** Les vies en coeurs : pleins pour ce qu'il reste, eteints pour ce qui est perdu. */
    static Component hearts(int remaining, int total) {
        Component full = Component.text("❤".repeat(Math.max(0, remaining)), NamedTextColor.RED);
        int lost = Math.max(0, total - remaining);
        return lost == 0 ? full
                : full.append(Component.text("❤".repeat(lost), NamedTextColor.DARK_GRAY));
    }

    /** Jauge en dix crans, pour l'avancement du remplissage de la piscine. */
    static Component gauge(int value, int max) {
        int filled = max <= 0 ? 0 : Math.round(10f * value / max);
        return Component.text("▓".repeat(filled), NamedTextColor.AQUA)
                .append(Component.text("▓".repeat(10 - filled), NamedTextColor.DARK_GRAY));
    }

    void title(Collection<Player> players, String key, String subKey, String... placeholders) {
        players.forEach(player -> title(player, key, subKey, placeholders));
    }

    void sound(Collection<Player> players, Sound sound, float pitch) {
        if (!plugin.ui("sounds")) {
            return;
        }
        players.forEach(player -> player.playSound(player.getLocation(), sound, 1f, pitch));
    }

    void bar(Arena arena, Collection<Player> viewers, String key, float progress,
            BossBar.Color color, String... placeholders) {
        if (!plugin.ui("bossbar")) {
            return;
        }
        BossBar bar = bars.computeIfAbsent(arena.id(),
                id -> BossBar.bossBar(Component.empty(), 1f, color, BossBar.Overlay.PROGRESS));
        bar.name(plugin.message(key, placeholders));
        bar.progress(Math.clamp(progress, 0f, 1f));
        bar.color(color);
        viewers.forEach(player -> player.showBossBar(bar));
    }

    void hideBar(Arena arena, Collection<Player> viewers) {
        BossBar bar = bars.get(arena.id());
        if (bar != null) {
            viewers.forEach(player -> player.hideBossBar(bar));
        }
    }

    void board(Arena arena, List<Component> lines, Collection<Player> viewers) {
        if (!plugin.ui("scoreboard")) {
            return;
        }
        Scoreboard board = boards.computeIfAbsent(arena.id(),
                id -> Bukkit.getScoreboardManager().getNewScoreboard());
        Objective objective = board.getObjective("thimble");
        if (objective == null) {
            objective = board.registerNewObjective("thimble", Criteria.DUMMY,
                    plugin.message("scoreboard.title", "arena", arena.id()));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        board.getEntries().forEach(board::resetScores);
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            // Les entrees doivent etre uniques : un "§r" invisible par ligne suffit.
            String entry = LegacyComponentSerializer.legacySection().serialize(lines.get(i)) + "§r".repeat(i);
            objective.getScore(entry).setScore(score--);
        }
        viewers.forEach(player -> player.setScoreboard(board));
    }

    /** Trainee de particules derriere le sauteur. */
    void trail(Location location, Collection<Player> viewers) {
        if (!plugin.ui("particles")) {
            return;
        }
        viewers.forEach(player -> player.spawnParticle(Particle.END_ROD, location, 1, 0, 0, 0, 0));
    }

    void burst(Location location, Collection<Player> viewers) {
        if (!plugin.ui("particles")) {
            return;
        }
        viewers.forEach(player -> player.spawnParticle(Particle.HAPPY_VILLAGER, location, 40, 0.6, 0.6, 0.6, 0));
    }

    /** Rend au joueur son tableau de score et retire les barres du plugin. */
    void clear(Player player) {
        bars.values().forEach(player::hideBossBar);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    void forget(Arena arena) {
        bars.remove(arena.id());
        boards.remove(arena.id());
    }
}
