package fr.thimble;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
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
        if (!plugin.ui("titles")) {
            return;
        }
        Component subtitle = subKey == null ? Component.empty() : plugin.message(subKey, placeholders);
        player.showTitle(Title.title(plugin.message(key, placeholders), subtitle, TIMES));
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
