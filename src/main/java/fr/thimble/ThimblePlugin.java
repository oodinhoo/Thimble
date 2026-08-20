package fr.thimble;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

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
        return getConfig().getInt("game.countdown-seconds", 15);
    }

    /** Temps passe dans l'eau apres un saut reussi, avant le retour aux gradins. */
    int splashPauseTicks() {
        return getConfig().getInt("game.splash-pause-seconds", 2) * 20;
    }

    /** Hauteur du point de vue au-dessus de la piscine pour choisir la case bonus. */
    int bonusViewHeight() {
        return getConfig().getInt("game.bonus-view-height", 12);
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

    // ------------------------------------------------------------------
    //  Baguette de configuration
    // ------------------------------------------------------------------

    /** Une configuration d'arene en cours : l'arene visee et l'etape suivante. */
    private record Setup(String arena, int step) { }

    private static final List<String> SETUP_STEPS = List.of("pos1", "pos2", "setjump", "gradins");

    private final Map<UUID, Setup> setups = new HashMap<>();

    /**
     * Le bloc vise par le joueur, fluides compris : l'eau n'etant pas solide, le client
     * n'envoie jamais de clic dessus, c'est au serveur de la trouver.
     */
    static Block targetBlock(Player player) {
        RayTraceResult trace = player.getWorld().rayTraceBlocks(player.getEyeLocation(),
                player.getEyeLocation().getDirection(), 60, FluidCollisionMode.ALWAYS, true);
        return trace == null ? null : trace.getHitBlock();
    }

    void startSetup(Player player, Arena arena) {
        setups.put(player.getUniqueId(), new Setup(arena.id(), 0));
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        wand.editMeta(meta -> meta.displayName(message("setup.wand-name")));
        player.getInventory().addItem(wand);
        send(player, "setup.started", "arena", arena.id());
        send(player, "setup.step-" + SETUP_STEPS.get(0));
    }

    /** @return true si le clic droit a servi a configurer une arene. */
    boolean handleWandClick(Player player) {
        Setup setup = setups.get(player.getUniqueId());
        if (setup == null || player.getInventory().getItemInMainHand().getType() != Material.BLAZE_ROD) {
            return false;
        }
        Arena arena = arena(setup.arena());
        Block target = targetBlock(player);
        if (arena == null || target == null) {
            setups.remove(player.getUniqueId());
            return false;
        }
        Location spot = target.getLocation().add(0.5, 1, 0.5);
        spot.setYaw(player.getLocation().getYaw());
        String step = SETUP_STEPS.get(setup.step());
        switch (step) {
            case "pos1", "pos2" -> arena.setLocation(step, target.getLocation());
            case "setjump" -> arena.setLocation("setjump", spot);
            // Les gradins servent a la fois de lobby d'attente et de point de retour.
            case "gradins" -> {
                arena.setLocation("setwait", spot);
                arena.setLocation("setspectator", spot);
            }
            default -> { }
        }
        saveArenas();
        int next = setup.step() + 1;
        if (next < SETUP_STEPS.size()) {
            setups.put(player.getUniqueId(), new Setup(setup.arena(), next));
            send(player, "setup.step-" + SETUP_STEPS.get(next));
            return true;
        }
        setups.remove(player.getUniqueId());
        player.getInventory().setItemInMainHand(null);
        List<String> missing = arena.missing();
        if (missing.isEmpty()) {
            arena.setEnabled(true);
            saveArenas();
            send(player, "setup.done", "arena", arena.id(), "water", String.valueOf(arena.waterLeft()));
        } else {
            send(player, "arena.incomplete", "missing", String.join(", ", missing));
        }
        return true;
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
