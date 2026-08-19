package fr.thimble;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class ThimblePlugin extends JavaPlugin {

    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Hud hud = new Hud(this);
    private YamlConfiguration messages;
    private Component prefix = Component.empty();
    private File arenasFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        arenasFile = new File(getDataFolder(), "arenas.yml");
        reload();
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        ThimbleCommand command = new ThimbleCommand(this);
        Objects.requireNonNull(getCommand("thimble")).setExecutor(command);
        Objects.requireNonNull(getCommand("thimble")).setTabCompleter(command);
        getServer().getScheduler().runTaskTimer(this, () -> arenas.values().forEach(Arena::tick), 1L, 1L);
    }

    @Override
    public void onDisable() {
        arenas.values().forEach(Arena::shutdown);
    }

    // ------------------------------------------------------------------
    //  Arenes
    // ------------------------------------------------------------------

    void reload() {
        arenas.values().forEach(Arena::shutdown);
        arenas.clear();
        reloadConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        prefix = miniMessage.deserialize(messages.getString("prefix", ""));
        YamlConfiguration stored = YamlConfiguration.loadConfiguration(arenasFile);
        for (String id : stored.getKeys(false)) {
            ConfigurationSection section = stored.getConfigurationSection(id);
            if (section != null) {
                Arena arena = new Arena(this, id);
                arena.load(section);
                arenas.put(id, arena);
            }
        }
    }

    void saveArenas() {
        YamlConfiguration stored = new YamlConfiguration();
        arenas.forEach((id, arena) -> arena.save(stored.createSection(id)));
        try {
            stored.save(arenasFile);
        } catch (IOException e) {
            getLogger().severe("Impossible d'enregistrer arenas.yml : " + e.getMessage());
        }
    }

    Arena arena(String id) {
        return arenas.get(id.toLowerCase());
    }

    Arena createArena(String id) {
        Arena arena = new Arena(this, id.toLowerCase());
        arenas.put(arena.id(), arena);
        saveArenas();
        return arena;
    }

    void deleteArena(Arena arena) {
        arena.shutdown();
        hud.forget(arena);
        arenas.remove(arena.id());
        saveArenas();
    }

    Collection<Arena> arenas() {
        return arenas.values();
    }

    /** L'arene ou se trouve ce joueur, ou null. */
    Arena arenaOf(Player player) {
        for (Arena arena : arenas.values()) {
            if (arena.contains(player)) {
                return arena;
            }
        }
        return null;
    }

    /** L'arene libre la plus remplie. */
    Arena bestFreeArena() {
        return arenas.values().stream()
                .filter(Arena::joinable)
                .max((a, b) -> Integer.compare(a.playerCount(), b.playerCount()))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    //  Configuration
    // ------------------------------------------------------------------

    int countdownSeconds() {
        return getConfig().getInt("game.countdown-seconds", 30);
    }

    int countdownFullSeconds() {
        return getConfig().getInt("game.countdown-full-seconds", 5);
    }

    int endDelaySeconds() {
        return getConfig().getInt("game.end-delay-seconds", 8);
    }

    int freezeTicks() {
        return getConfig().getInt("game.freeze-ticks", 20);
    }

    boolean refillWhenFull() {
        return getConfig().getBoolean("refill-when-full", true);
    }

    int bonusSelectSeconds() {
        return getConfig().getInt("game.bonus-select-seconds", 8);
    }

    int rejoinGraceSeconds() {
        return getConfig().getInt("game.rejoin-grace-seconds", 60);
    }

    boolean waitAsSpectator() {
        return getConfig().getBoolean("game.wait-as-spectator", true);
    }

    /** Element d'habillage active ? (titles, sounds, bossbar, scoreboard, particles) */
    boolean ui(String element) {
        return getConfig().getBoolean("ui." + element, true);
    }

    Hud hud() {
        return hud;
    }

    List<Material> defaultPalette() {
        List<Material> palette = new ArrayList<>();
        for (String name : getConfig().getStringList("default-palette")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                palette.add(material);
            }
        }
        if (palette.isEmpty()) {
            palette.add(Material.WHITE_WOOL);
        }
        return palette;
    }

    // ------------------------------------------------------------------
    //  Messages
    // ------------------------------------------------------------------

    /** @param placeholders paires cle/valeur : "player", "Alex", "lives", "2"... */
    Component message(String key, String... placeholders) {
        TagResolver[] resolvers = new TagResolver[placeholders.length / 2];
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolvers[i / 2] = Placeholder.unparsed(placeholders[i], placeholders[i + 1]);
        }
        return messageResolved(key, resolvers);
    }

    Component messageResolved(String key, TagResolver... resolvers) {
        String raw = messages.getString(key);
        return raw == null ? Component.text(key) : miniMessage.deserialize(raw, TagResolver.resolver(resolvers));
    }

    /** Le libelle colore d'un etat d'arene, injectable via &lt;state&gt;. */
    TagResolver stateTag(Arena arena) {
        return Placeholder.component("state", messageResolved("state." + arena.state().name()));
    }

    void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(prefix.append(message(key, placeholders)));
    }
}
