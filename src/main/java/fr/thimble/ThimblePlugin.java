package fr.thimble;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    static final List<String> LANGUAGES = List.of("fr", "en");

    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Hud hud = new Hud(this);
    private final Leaderboard leaderboard = new Leaderboard(this);
    private final Map<UUID, Arena> byPlayer = new HashMap<>();
    private YamlConfiguration messages;
    private Component prefix = Component.empty();
    private File arenasFile;
    private File pendingFile;
    private YamlConfiguration pending;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String language : LANGUAGES) {
            if (!new File(getDataFolder(), messagesFile(language)).exists()) {
                saveResource(messagesFile(language), false);
            }
        }
        arenasFile = new File(getDataFolder(), "arenas.yml");
        pendingFile = new File(getDataFolder(), "pending-restore.yml");
        pending = YamlConfiguration.loadConfiguration(pendingFile);
        reload();
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        ThimbleCommand command = new ThimbleCommand(this);
        Objects.requireNonNull(getCommand("thimble")).setExecutor(command);
        Objects.requireNonNull(getCommand("thimble")).setTabCompleter(command);
        getServer().getScheduler().runTaskTimer(this, this::tickArenas, 1L, 1L);
    }

    /** An arena that throws would stop the next ones, twenty times a second: isolate it. */
    private void tickArenas() {
        for (Arena arena : arenas.values()) {
            try {
                arena.tick();
            } catch (RuntimeException e) {
                getLogger().severe("Arena " + arena.id() + " disabled after an error: " + e);
                arena.setEnabled(false);
                saveArenas();
            }
        }
    }

    @Override
    public void onDisable() {
        arenas.values().forEach(Arena::shutdown);
    }

    // ------------------------------------------------------------------
    //  Arenas
    // ------------------------------------------------------------------

    void reload() {
        arenas.values().forEach(Arena::shutdown);
        arenas.clear();
        setups.clear();
        reloadConfig();
        loadMessages();
        YamlConfiguration stored = YamlConfiguration.loadConfiguration(arenasFile);
        for (String id : stored.getKeys(false)) {
            ConfigurationSection section = stored.getConfigurationSection(id);
            if (section != null) {
                Arena arena = new Arena(this, id);
                arena.load(section);
                arenas.put(id, arena);
            }
        }
        // After the messages: rendering the board uses them as soon as it loads.
        leaderboard.load();
    }

    /** French keeps the historical name, so already customized files are not lost. */
    static String messagesFile(String language) {
        return language.equals("fr") ? "messages.yml" : "messages_" + language + ".yml";
    }

    private void loadMessages() {
        String language = getConfig().getString("language", "en").toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(language)) {
            getLogger().warning("Unknown language: " + language + ", falling back to en.");
            language = "en";
        }
        String file = messagesFile(language);
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), file));
        // The jar's texts are the fallback: an update that adds messages does not require
        // deleting the existing file, and customizations are kept.
        try (InputStream bundled = getResource(file)) {
            if (bundled != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            getLogger().warning("Could not read the default messages: " + e.getMessage());
        }
        prefix = miniMessage.deserialize(messages.getString("prefix", ""));
    }

    /** Switches the language without stopping games: only texts and the board are reloaded. */
    void setLanguage(String language) {
        getConfig().set("language", language);
        saveConfig();
        loadMessages();
        leaderboard.refresh();
    }

    void saveArenas() {
        YamlConfiguration stored = new YamlConfiguration();
        arenas.forEach((id, arena) -> arena.save(stored.createSection(id)));
        try {
            stored.save(arenasFile);
        } catch (IOException e) {
            getLogger().severe("Could not save arenas.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Pending restorations
    // ------------------------------------------------------------------

    /**
      * A player's state is written to disk as soon as they enter an arena. If the server goes down
      * during the game, its vanilla save holds an empty inventory: this file is what
      * gives it back on their next login.
     */
    void remember(Player player, PlayerSnapshot snapshot) {
        snapshot.save(pending.createSection(player.getUniqueId().toString()));
        savePending();
    }

    void forget(UUID uuid) {
        if (pending.contains(uuid.toString())) {
            pending.set(uuid.toString(), null);
            savePending();
        }
    }

    /** @return true if the player had a pending state, now given back. */
    boolean restorePending(Player player) {
        ConfigurationSection section = pending.getConfigurationSection(player.getUniqueId().toString());
        if (section == null) {
            return false;
        }
        PlayerSnapshot.load(section).restore(player);
        forget(player.getUniqueId());
        getLogger().info("Restored the state of " + player.getName() + " after an interrupted game.");
        return true;
    }

    // ponytail: a single file rewritten in full on every join and leave, so O(players in
    // arenas) per write. Invisible below a dozen simultaneous players; move to one
    // file per player the day several arenas run full at the same time.
    private void savePending() {
        try {
            pending.save(pendingFile);
        } catch (IOException e) {
            getLogger().severe("Could not save pending-restore.yml: " + e.getMessage());
        }
    }

    Arena arena(String id) {
        return arenas.get(id.toLowerCase(Locale.ROOT));
    }

    /** Bukkit reads the dot as a path separator: such an id would nest the section. */
    static boolean validId(String id) {
        return id.matches("[a-z0-9_-]{1,32}");
    }

    Arena createArena(String id) {
        Arena arena = new Arena(this, id.toLowerCase(Locale.ROOT));
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

    /**
      * The arena this player is in, or null. Indexed rather than scanned: called on every
      * movement packet of every connected player, including those not playing.
     */
    Arena arenaOf(Player player) {
        return byPlayer.get(player.getUniqueId());
    }

    void track(Player player, Arena arena) {
        byPlayer.put(player.getUniqueId(), arena);
    }

    void untrack(UUID uuid) {
        byPlayer.remove(uuid);
        setups.remove(uuid);
    }

    /** The most filled free arena. */
    Arena bestFreeArena() {
        return arenas.values().stream()
                .filter(Arena::joinable)
                .max((a, b) -> Integer.compare(a.playerCount(), b.playerCount()))
                .orElse(null);
    }

    /** An enabled arena, free and empty: enough to start a solo game. */
    Arena freeEmptyArena() {
        return arenas.values().stream()
                .filter(arena -> arena.joinable() && arena.playerCount() == 0)
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    //  Configuration
    // ------------------------------------------------------------------

    int countdownSeconds() {
        return getConfig().getInt("game.countdown-seconds", 15);
    }

    /** Time spent in the water after a successful jump, before going back to the stands. */
    int splashPauseSeconds() {
        return getConfig().getInt("game.splash-pause-seconds", 2);
    }

    /** Height of the viewpoint above the pool to pick the bonus block. */
    int bonusViewHeight() {
        return getConfig().getInt("game.bonus-view-height", 12);
    }

    int countdownFullSeconds() {
        return getConfig().getInt("game.countdown-full-seconds", 5);
    }

    int endDelaySeconds() {
        return getConfig().getInt("game.end-delay-seconds", 8);
    }

    int freezeSeconds() {
        return getConfig().getInt("game.freeze-seconds", 1);
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

    /** Presentation element enabled? (titles, sounds, bossbar, scoreboard, particles) */
    boolean ui(String element) {
        return getConfig().getBoolean("ui." + element, true);
    }

    Hud hud() {
        return hud;
    }

    Leaderboard leaderboard() {
        return leaderboard;
    }

    // ------------------------------------------------------------------
    //  Setup wand
    // ------------------------------------------------------------------

    /** An arena setup in progress: the target arena and the next step. */
    private record Setup(String arena, int step) { }

    private static final List<String> SETUP_STEPS = List.of("pos1", "pos2", "setjump", "gradins");

    private final Map<UUID, Setup> setups = new HashMap<>();

    /**
      * The block the player is aiming at, fluids included: water not being solid, the client
      * never sends a click on it, the server has to find it.
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

    /** @return true if the right click was used to set up an arena. */
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
            // The stands are both the waiting lobby and the return point.
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

    /** @param placeholders key/value pairs: "player", "Alex", "lives", "2"... */
    Component message(String key, String... placeholders) {
        return messageResolved(key, resolvers(placeholders));
    }

    /** Key/value pairs as MiniMessage tags, the value not interpreted. */
    static TagResolver[] resolvers(String... placeholders) {
        TagResolver[] resolvers = new TagResolver[placeholders.length / 2];
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolvers[i / 2] = Placeholder.unparsed(placeholders[i], placeholders[i + 1]);
        }
        return resolvers;
    }

    Component messageResolved(String key, TagResolver... resolvers) {
        String raw = messages.getString(key);
        return raw == null ? Component.text(key) : miniMessage.deserialize(raw, TagResolver.resolver(resolvers));
    }

    /** The raw text of a key, to insert as is in another message (argument names...). */
    String text(String key) {
        return messages.getString(key, key);
    }

    /** The colored label of an arena state, injectable through &lt;state&gt;. */
    TagResolver stateTag(Arena arena) {
        return Placeholder.component("state", messageResolved("state." + arena.state().name()));
    }

    void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(prefix.append(message(key, placeholders)));
    }

    /**
      * Click on a sign, as a toggle: already in a game, the player leaves it (like {@code /th
      * leave}); otherwise they join, like {@code /th join}: arena named on the second line, otherwise
      * the most filled free arena.
     */
    void signClick(Player player, String arenaId) {
        Arena current = arenaOf(player);
        if (current != null) {
            current.leave(player, true);
            return;
        }
        // The sign is another way in than /th join: same permissions.
        if (!player.hasPermission("thimble.play")) {
            send(player, "general.no-permission");
            return;
        }
        boolean named = arenaId != null && !arenaId.isBlank();
        Arena arena = named ? arena(arenaId) : bestFreeArena();
        if (arena == null) {
            send(player, named ? "arena.unknown" : "game.no-free-arena", "arena", named ? arenaId : "");
            return;
        }
        String error = arena.join(player);
        if (error != null) {
            send(player, error, "arena", arena.id());
        }
    }
}
