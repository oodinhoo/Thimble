package fr.thimble;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.bukkit.scoreboard.Team;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
  * Presentation: titles, sounds, boss bar, scoreboard, particles.
  * Each element can be turned off in config.yml ({@code ui} section).
 */
final class Hud {

    private static final Title.Times TIMES =
            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1200), Duration.ofMillis(400));

    private final ThimblePlugin plugin;
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Scoreboard> boards = new HashMap<>();
    /** The scoreboard the player had before joining: another plugin may be using it. */
    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();

    Hud(ThimblePlugin plugin) {
        this.plugin = plugin;
    }

    void title(Player player, String key, String subKey, String... placeholders) {
        titleResolved(player, key, subKey, ThimblePlugin.resolvers(placeholders));
    }

    void titleResolved(Player player, String key, String subKey, TagResolver... resolvers) {
        if (!plugin.ui("titles")) {
            return;
        }
        Component subtitle = subKey == null ? Component.empty() : plugin.messageResolved(subKey, resolvers);
        player.showTitle(Title.title(plugin.messageResolved(key, resolvers), subtitle, TIMES));
    }

    /** Current instruction, above the hotbar. Sent again every second, otherwise it fades. */
    void actionBar(Collection<Player> players, String key, String... placeholders) {
        if (!plugin.ui("actionbar")) {
            return;
        }
        Component text = plugin.message(key, placeholders);
        players.forEach(player -> player.sendActionBar(text));
    }

    /** The Bukkit color of a dyed block ({@code RED_}, {@code BLUE_}... prefix), white by default. */
    static Color dyeColor(Material block) {
        for (DyeColor dye : DyeColor.values()) {
            if (block != null && block.name().startsWith(dye.name() + "_")) {
                return dye.getColor();
            }
        }
        return Color.WHITE;
    }

    /** A player's colored square, derived from the block assigned to them. */
    static Component square(Material block) {
        Color color = dyeColor(block);
        return Component.text("■", TextColor.color(color.getRed(), color.getGreen(), color.getBlue()));
    }

    /** Lives as hearts: full for what is left, dimmed for what is lost. */
    static Component hearts(int remaining, int total) {
        Component full = Component.text("❤".repeat(Math.max(0, remaining)), NamedTextColor.RED);
        int lost = Math.max(0, total - remaining);
        return lost == 0 ? full
                : full.append(Component.text("❤".repeat(lost), NamedTextColor.DARK_GRAY));
    }

    /** Ten-step gauge, for how far the pool is filled. */
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
            // Entries must be unique: one invisible "§r" per line is enough.
            String entry = LegacyComponentSerializer.legacySection().serialize(lines.get(i)) + "§r".repeat(i);
            objective.getScore(entry).setScore(score--);
        }
        viewers.forEach(player -> {
            previousBoards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
            player.setScoreboard(board);
        });
    }

    /**
      * Puts the player in a team colored like their block, on the arena scoreboard.
      * The team color tints the player's dot on the locator bar (the "radar")
      * and their name above their head: no waypoint API exists in Paper 26.3, the team is
      * the only server-side lever, limited to the 16 named colors (hence {@link NamedTextColor#nearestTo}).
     */
    void colorPlayer(Arena arena, Player player, Material block) {
        if (!plugin.ui("radar")) {
            return;
        }
        Color rgb = dyeColor(block);
        NamedTextColor named = NamedTextColor.nearestTo(
                TextColor.color(rgb.getRed(), rgb.getGreen(), rgb.getBlue()));
        Scoreboard board = boards.computeIfAbsent(arena.id(),
                id -> Bukkit.getScoreboardManager().getNewScoreboard());
        String teamName = "th" + Integer.toHexString(named.value()); // one team per color, reused
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.color(named);
        }
        team.addEntry(player.getName());
        previousBoards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
        player.setScoreboard(board);
    }

    /** Particle trail behind the jumper, in the color of their block. */
    void trail(Location location, Material block, Collection<Player> viewers) {
        if (!plugin.ui("particles")) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(dyeColor(block), 1.2f);
        viewers.forEach(player -> player.spawnParticle(Particle.DUST, location, 2, 0, 0, 0, 0, dust));
    }

    void burst(Location location, Collection<Player> viewers) {
        if (!plugin.ui("particles")) {
            return;
        }
        viewers.forEach(player -> player.spawnParticle(Particle.HAPPY_VILLAGER, location, 40, 0.6, 0.6, 0.6, 0));
    }

    /** Gives the player their scoreboard back and removes the plugin's bars. */
    void clear(Player player) {
        bars.values().forEach(player::hideBossBar);
        Scoreboard previous = previousBoards.remove(player.getUniqueId());
        player.setScoreboard(previous != null ? previous : Bukkit.getScoreboardManager().getMainScoreboard());
    }

    void forget(Arena arena) {
        bars.remove(arena.id());
        boards.remove(arena.id());
    }
}
