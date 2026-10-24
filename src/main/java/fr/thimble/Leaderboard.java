package fr.thimble;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;

/**
 * Classement persistant du serveur et resultat de la derniere partie, affiches sur un
 * {@link TextDisplay} pose dans le monde. Seules les parties multijoueur terminees normalement
 * comptent : le solo est de l'entrainement, et un forcestop passe par {@code shutdown()} sans
 * jamais atteindre {@code end()}, donc hors de ce qui est enregistre ici.
 */
final class Leaderboard {

    /** Marque l'entite du tableau, pour la retrouver et tuer les doublons apres un reload. */
    private static final String TAG = "thimble_board";

    /** Stats cumulees d'un joueur. Champs mutables : tout passe par {@link #record}. */
    static final class Stats {
        final UUID uuid;
        String name;
        int wins;
        int games;
        int thimbles;
        int hits;

        Stats(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    /** Une ligne du podium de la derniere partie, telle que l'arene la fournit. */
    record ResultLine(UUID uuid, String name, int hits, int thimbles) { }

    private final ThimblePlugin plugin;
    private final Map<UUID, Stats> stats = new HashMap<>();
    private File file;
    private Location board;
    private String lastArena;
    private String lastWinner;
    // "nom|cases|des" pre-decoupe : le podium de la derniere partie, au plus trois lignes.
    private List<String> lastTop = new ArrayList<>();

    Leaderboard(ThimblePlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        file = new File(plugin.getDataFolder(), "stats.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        stats.clear();
        ConfigurationSection players = yml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID uuid = tryUuid(key);
                ConfigurationSection s = players.getConfigurationSection(key);
                if (uuid == null || s == null) {
                    continue;
                }
                Stats st = new Stats(uuid, s.getString("name", key));
                st.wins = s.getInt("wins");
                st.games = s.getInt("games");
                st.thimbles = s.getInt("thimbles");
                st.hits = s.getInt("hits");
                stats.put(uuid, st);
            }
        }
        board = yml.getLocation("board");
        lastArena = yml.getString("last-game.arena");
        lastWinner = yml.getString("last-game.winner");
        lastTop = yml.getStringList("last-game.top");
        refresh();
    }

    private static UUID tryUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        YamlConfiguration yml = new YamlConfiguration();
        for (Stats s : stats.values()) {
            String p = "players." + s.uuid;
            yml.set(p + ".name", s.name);
            yml.set(p + ".wins", s.wins);
            yml.set(p + ".games", s.games);
            yml.set(p + ".thimbles", s.thimbles);
            yml.set(p + ".hits", s.hits);
        }
        yml.set("board", board);
        yml.set("last-game.arena", lastArena);
        yml.set("last-game.winner", lastWinner);
        yml.set("last-game.top", lastTop);
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible d'enregistrer stats.yml : " + e.getMessage());
        }
    }

    /** Fin d'une partie multi : incremente les stats, retient le podium, reecrit et rafraichit. */
    void record(String arena, UUID winner, List<ResultLine> lines) {
        for (ResultLine line : lines) {
            Stats s = stats.computeIfAbsent(line.uuid(), u -> new Stats(u, line.name()));
            s.name = line.name();
            s.games++;
            s.hits += line.hits();
            s.thimbles += line.thimbles();
            if (line.uuid().equals(winner)) {
                s.wins++;
            }
        }
        lastArena = arena;
        lastWinner = winner == null ? null : nameOf(winner, lines);
        lastTop = new ArrayList<>();
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            ResultLine l = lines.get(i);
            lastTop.add(l.name() + "|" + l.hits() + "|" + l.thimbles());
        }
        save();
        refresh();
    }

    private static String nameOf(UUID uuid, List<ResultLine> lines) {
        for (ResultLine l : lines) {
            if (l.uuid().equals(uuid)) {
                return l.name();
            }
        }
        return "?";
    }

    /** Tri pur, testable sans serveur : victoires decroissantes, puis des a coudre. */
    static List<Stats> top(Collection<Stats> all, int size) {
        return all.stream()
                .sorted(Comparator.comparingInt((Stats s) -> s.wins)
                        .thenComparingInt(s -> s.thimbles).reversed())
                .limit(Math.max(0, size))
                .toList();
    }

    // ------------------------------------------------------------------
    //  Entite d'affichage
    // ------------------------------------------------------------------

    void setBoard(Location location) {
        clearDisplays();
        board = location;
        save();
        refresh();
    }

    void clearBoard() {
        clearDisplays();
        board = null;
        save();
    }

    boolean hasBoard() {
        return board != null;
    }

    /** Supprime toute entite de tableau dans les mondes charges : reload, crash ou deplacement. */
    private void clearDisplays() {
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (d.getScoreboardTags().contains(TAG)) {
                    d.remove();
                }
            }
        }
    }

    /** (Re)affiche le tableau. Appele a l'activation et en fin de partie, jamais au tick. */
    void refresh() {
        if (board == null || board.getWorld() == null) {
            return;
        }
        if (!plugin.ui("leaderboard")) {
            clearDisplays(); // l'entite est persistante : sans ca, elle resterait figee dans le monde
            return;
        }
        // ponytail: force le chargement du chunk du tableau. Un seul chunk de lobby garde charge,
        // c'est justement la que les joueurs lisent le classement ; a revoir si ca pose un cout.
        board.getChunk().load();
        display().text(render());
    }

    private TextDisplay display() {
        World w = board.getWorld();
        TextDisplay found = null;
        for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
            if (!d.getScoreboardTags().contains(TAG)) {
                continue;
            }
            if (found == null) {
                found = d;
            } else {
                d.remove(); // doublons apres un reload ou un crash
            }
        }
        if (found == null) {
            found = w.spawn(board, TextDisplay.class, d -> {
                d.addScoreboardTag(TAG);
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(true);
            });
        }
        return found;
    }

    private Component render() {
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.message("leaderboard.title"));
        List<Stats> ranked = top(stats.values(), plugin.getConfig().getInt("leaderboard.size", 10));
        if (ranked.isEmpty()) {
            lines.add(plugin.message("leaderboard.empty"));
        } else {
            int rank = 1;
            for (Stats s : ranked) {
                lines.add(plugin.message("leaderboard.line",
                        "rank", String.valueOf(rank++),
                        "player", s.name,
                        "wins", String.valueOf(s.wins),
                        "thimbles", String.valueOf(s.thimbles)));
            }
        }
        lines.add(plugin.message("leaderboard.last-header", "arena", lastArena == null ? "-" : lastArena));
        if (lastTop.isEmpty()) {
            lines.add(plugin.message("leaderboard.last-none"));
        } else {
            lines.add(plugin.message("leaderboard.last-winner", "player", lastWinner == null ? "-" : lastWinner));
            int rank = 1;
            for (String entry : lastTop) {
                String[] parts = entry.split("\\|", -1);
                lines.add(plugin.message("leaderboard.last-line",
                        "rank", String.valueOf(rank++),
                        "player", parts.length > 0 ? parts[0] : "?",
                        "hits", parts.length > 1 ? parts[1] : "0",
                        "thimbles", parts.length > 2 ? parts[2] : "0"));
            }
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
