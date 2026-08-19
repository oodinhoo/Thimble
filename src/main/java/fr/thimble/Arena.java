package fr.thimble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Une arene : sa configuration, et la partie qui s'y deroule.
 * Tout est pilote par {@link #tick()}, appele chaque tick par le plugin.
 */
final class Arena {

    enum State { DISABLED, WAITING, STARTING, PLAYING, ENDING }

    /** Un joueur dans la partie en cours. */
    private static final class Seat {
        final PlayerSnapshot snapshot;
        Material block;
        int lives;
        boolean alive = true;

        Seat(PlayerSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private final ThimblePlugin plugin;
    private final String id;

    // --- configuration ---
    private Location wait;
    private Location jump;
    private Location spectator;
    private Location corner1;
    private Location corner2;
    private Integer surface;
    private int minPlayers = 2;
    private int maxPlayers = 8;
    private int lives = 3;
    private int timeout = 15;
    private boolean enabled;
    private final List<Material> palette = new ArrayList<>();

    // --- partie en cours ---
    private State state = State.WAITING;
    private final Map<UUID, Seat> seats = new LinkedHashMap<>();
    private final List<UUID> order = new ArrayList<>();
    private final Map<Block, BlockData> changed = new LinkedHashMap<>();
    private UUID jumper;
    private int index;
    private int round;
    private int ticks;
    private int freeze;
    private boolean falling;

    Arena(ThimblePlugin plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    String id() {
        return id;
    }

    State state() {
        return enabled ? state : State.DISABLED;
    }

    int playerCount() {
        return seats.size();
    }

    int maxPlayers() {
        return maxPlayers;
    }

    boolean contains(Player player) {
        return seats.containsKey(player.getUniqueId());
    }

    boolean joinable() {
        return enabled && (state == State.WAITING || state == State.STARTING) && seats.size() < maxPlayers;
    }

    // ------------------------------------------------------------------
    //  Entree / sortie
    // ------------------------------------------------------------------

    /** @return la cle du message d'erreur, ou null si le joueur a bien rejoint. */
    String join(Player player) {
        if (!enabled) {
            return "arena.disabled";
        }
        if (state == State.PLAYING || state == State.ENDING) {
            return "game.in-progress";
        }
        if (seats.size() >= maxPlayers) {
            return "game.full";
        }
        seats.put(player.getUniqueId(), new Seat(PlayerSnapshot.capture(player)));
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFallDistance(0);
        player.teleport(wait);
        broadcast("game.joined", "player", player.getName(),
                "players", String.valueOf(seats.size()), "max", String.valueOf(maxPlayers));
        return null;
    }

    void leave(Player player, boolean announce) {
        UUID uuid = player.getUniqueId();
        Seat seat = seats.remove(uuid);
        if (seat == null) {
            return;
        }
        order.remove(uuid);
        seat.snapshot.restore(player);
        if (announce) {
            broadcast("game.left", "player", player.getName(),
                    "players", String.valueOf(seats.size()), "max", String.valueOf(maxPlayers));
        }
        if (state == State.PLAYING) {
            if (uuid.equals(jumper)) {
                jumper = null;
                broadcast("game.quit-during-turn", "player", player.getName());
                nextTurn();
            } else if (aliveCount() <= 1) {
                end();
            }
        } else if (state == State.STARTING && seats.size() < minPlayers) {
            state = State.WAITING;
            broadcast("game.countdown-cancelled");
        }
    }

    /** Arret propre : tout le monde est restaure, la piscine aussi. */
    void shutdown() {
        for (UUID uuid : new ArrayList<>(seats.keySet())) {
            Seat seat = seats.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && seat != null) {
                seat.snapshot.restore(player);
            }
        }
        order.clear();
        restorePool();
        jumper = null;
        state = State.WAITING;
    }

    void forceStop() {
        if (state == State.PLAYING || state == State.STARTING) {
            broadcast("game.stopped");
        }
        shutdown();
    }

    void forceStart() {
        if (state == State.WAITING && seats.size() >= 2) {
            start();
        }
    }

    // ------------------------------------------------------------------
    //  Boucle de jeu
    // ------------------------------------------------------------------

    void tick() {
        if (!enabled) {
            return;
        }
        switch (state) {
            case WAITING -> {
                if (seats.size() >= minPlayers) {
                    state = State.STARTING;
                    ticks = plugin.countdownSeconds() * 20;
                }
            }
            case STARTING -> {
                if (seats.size() < minPlayers) {
                    state = State.WAITING;
                    broadcast("game.countdown-cancelled");
                    return;
                }
                if (seats.size() >= maxPlayers) {
                    ticks = Math.min(ticks, plugin.countdownFullSeconds() * 20);
                }
                if (ticks % 20 == 0) {
                    int seconds = ticks / 20;
                    if (seconds == 30 || seconds == 20 || seconds == 10 || (seconds > 0 && seconds <= 5)) {
                        broadcast("game.countdown", "time", String.valueOf(seconds));
                    }
                }
                if (--ticks <= 0) {
                    start();
                }
            }
            case PLAYING -> {
                if (freeze > 0) {
                    freeze--;
                } else if (jumper != null && --ticks <= 0) {
                    miss("game.timeout");
                }
            }
            case ENDING -> {
                if (--ticks <= 0) {
                    shutdown();
                }
            }
            default -> { }
        }
    }

    private void start() {
        order.clear();
        order.addAll(seats.keySet());
        Collections.shuffle(order);
        List<Material> colors = palette.isEmpty() ? plugin.defaultPalette() : palette;
        int i = 0;
        for (UUID uuid : order) {
            Seat seat = seats.get(uuid);
            seat.block = colors.get(i++ % colors.size());
            seat.lives = lives;
            seat.alive = true;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.send(player, "game.your-color", "block", seat.block.name());
            }
        }
        state = State.PLAYING;
        round = 1;
        index = -1;
        broadcast("game.started", "lives", String.valueOf(lives));
        broadcast("game.round", "round", "1");
        nextTurn();
    }

    private void nextTurn() {
        if (state != State.PLAYING) {
            return;
        }
        if (aliveCount() <= 1) {
            end();
            return;
        }
        if (waterLeft() == 0) {
            if (!plugin.refillWhenFull()) {
                end();
                return;
            }
            restorePool();
            broadcast("game.pool-refilled");
        }
        Player player = null;
        for (int i = 0; i < order.size() + 1 && player == null; i++) {
            index++;
            if (index >= order.size()) {
                index = 0;
                round++;
                broadcast("game.round", "round", String.valueOf(round));
            }
            Seat seat = seats.get(order.get(index));
            if (seat != null && seat.alive) {
                player = Bukkit.getPlayer(order.get(index));
            }
        }
        if (player == null) {
            end();
            return;
        }
        jumper = player.getUniqueId();
        falling = false;
        freeze = plugin.freezeTicks();
        ticks = timeout * 20;
        player.teleport(jump);
        player.setFallDistance(0);
        plugin.send(player, "game.your-turn");
        broadcast("game.turn-of", "player", player.getName());
    }

    /** Appele a chaque deplacement du sauteur en cours. */
    void handleMove(Player player) {
        if (state != State.PLAYING || freeze > 0 || jumper == null || !jumper.equals(player.getUniqueId())) {
            return;
        }
        Location location = player.getLocation();
        if (!falling) {
            if (player.isOnGround()) {
                return;
            }
            falling = true;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (inPool(x, z) && y <= surfaceY() && y >= floorY()) {
            Block target = world().getBlockAt(x, surfaceY(), z);
            if (target.getType() == Material.WATER) {
                hit(player, target);
                return;
            }
        }
        if (player.isOnGround() || location.getY() < floorY() - 5 || location.getY() < world().getMinHeight() + 1) {
            miss("game.miss");
        }
    }

    boolean frozen(Player player) {
        return freeze > 0 && jumper != null && jumper.equals(player.getUniqueId());
    }

    private void hit(Player player, Block target) {
        Seat seat = seats.get(player.getUniqueId());
        jumper = null;
        boolean thimble = isolated(target.getX(), target.getZ());
        place(target, seat.block);
        broadcast(thimble ? "game.thimble" : "game.hit", "player", player.getName());
        if (thimble) {
            // Bonus : une case supplementaire est remplie.
            // ponytail: case tiree au sort, pas choisie par le joueur -- selecteur a faire au lot 2.
            Block bonus = randomWater();
            if (bonus != null) {
                place(bonus, seat.block);
                broadcast("game.bonus-block");
            }
        }
        park(player);
        nextTurn();
    }

    private void miss(String messageKey) {
        UUID uuid = jumper;
        jumper = null;
        if (uuid == null) {
            return;
        }
        Seat seat = seats.get(uuid);
        Player player = Bukkit.getPlayer(uuid);
        String name = player != null ? player.getName() : "?";
        if (seat != null) {
            seat.lives--;
            if (seat.lives <= 0) {
                seat.alive = false;
                if (player != null) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                broadcast("game.eliminated", "player", name, "remaining", String.valueOf(aliveCount()));
            } else {
                broadcast(messageKey, "player", name, "lives", String.valueOf(seat.lives));
            }
        }
        if (player != null && seat != null && seat.alive) {
            park(player);
        }
        nextTurn();
    }

    private void end() {
        state = State.ENDING;
        jumper = null;
        ticks = plugin.endDelaySeconds() * 20;
        UUID winner = null;
        for (UUID uuid : order) {
            Seat seat = seats.get(uuid);
            if (seat != null && seat.alive) {
                winner = uuid;
                break;
            }
        }
        if (winner == null) {
            broadcast("game.draw");
        } else {
            Player player = Bukkit.getPlayer(winner);
            broadcast("game.win", "player", player != null ? player.getName() : "?");
        }
    }

    private void park(Player player) {
        player.setFallDistance(0);
        player.teleport(spectator != null ? spectator : wait);
    }

    private int aliveCount() {
        return (int) seats.values().stream().filter(seat -> seat.alive).count();
    }

    void broadcast(String key, String... placeholders) {
        for (UUID uuid : seats.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                plugin.send(player, key, placeholders);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Piscine
    // ------------------------------------------------------------------

    World world() {
        return corner1 != null ? corner1.getWorld() : null;
    }

    int surfaceY() {
        if (surface != null) {
            return surface;
        }
        return hasPool() ? Math.max(corner1.getBlockY(), corner2.getBlockY()) : 0;
    }

    private int floorY() {
        return Math.min(corner1.getBlockY(), corner2.getBlockY());
    }

    private boolean inPool(int x, int z) {
        return x >= Math.min(corner1.getBlockX(), corner2.getBlockX())
                && x <= Math.max(corner1.getBlockX(), corner2.getBlockX())
                && z >= Math.min(corner1.getBlockZ(), corner2.getBlockZ())
                && z <= Math.max(corner1.getBlockZ(), corner2.getBlockZ());
    }

    /** Vrai si les 4 cases voisines sont deja remplies (ou hors piscine) : un trou de 1x1. */
    private boolean isolated(int x, int z) {
        return !isWater(x + 1, z) && !isWater(x - 1, z) && !isWater(x, z + 1) && !isWater(x, z - 1);
    }

    private boolean isWater(int x, int z) {
        return inPool(x, z) && world().getBlockAt(x, surfaceY(), z).getType() == Material.WATER;
    }

    int waterLeft() {
        if (!hasPool()) {
            return 0;
        }
        int count = 0;
        for (int x = Math.min(corner1.getBlockX(), corner2.getBlockX()); x <= Math.max(corner1.getBlockX(), corner2.getBlockX()); x++) {
            for (int z = Math.min(corner1.getBlockZ(), corner2.getBlockZ()); z <= Math.max(corner1.getBlockZ(), corner2.getBlockZ()); z++) {
                if (world().getBlockAt(x, surfaceY(), z).getType() == Material.WATER) {
                    count++;
                }
            }
        }
        return count;
    }

    private Block randomWater() {
        List<Block> water = new ArrayList<>();
        for (int x = Math.min(corner1.getBlockX(), corner2.getBlockX()); x <= Math.max(corner1.getBlockX(), corner2.getBlockX()); x++) {
            for (int z = Math.min(corner1.getBlockZ(), corner2.getBlockZ()); z <= Math.max(corner1.getBlockZ(), corner2.getBlockZ()); z++) {
                Block block = world().getBlockAt(x, surfaceY(), z);
                if (block.getType() == Material.WATER) {
                    water.add(block);
                }
            }
        }
        return water.isEmpty() ? null : water.get(ThreadLocalRandom.current().nextInt(water.size()));
    }

    private void place(Block block, Material material) {
        changed.putIfAbsent(block, block.getBlockData());
        block.setType(material, false);
    }

    void restorePool() {
        changed.forEach((block, data) -> block.setBlockData(data, false));
        changed.clear();
    }

    boolean hasPool() {
        return corner1 != null && corner2 != null && corner1.getWorld() != null
                && corner1.getWorld().equals(corner2.getWorld());
    }

    // ------------------------------------------------------------------
    //  Configuration
    // ------------------------------------------------------------------

    /** @return la liste des elements manquants, vide si l'arene est prete. */
    List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (wait == null) {
            missing.add("setwait");
        }
        if (jump == null) {
            missing.add("setjump");
        }
        if (spectator == null) {
            missing.add("setspectator");
        }
        if (!hasPool()) {
            missing.add("pos1/pos2 (meme monde)");
        } else if (waterLeft() == 0) {
            missing.add("de l'eau a Y=" + surfaceY());
        }
        return missing;
    }

    boolean enabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            shutdown();
        }
    }

    void setLocation(String what, Location location) {
        switch (what) {
            case "setwait" -> wait = location;
            case "setjump" -> jump = location;
            case "setspectator" -> spectator = location;
            case "pos1" -> corner1 = location;
            case "pos2" -> corner2 = location;
            default -> throw new IllegalArgumentException(what);
        }
    }

    void setSurface(Integer y) {
        surface = y;
    }

    /** @return false si la cle est inconnue. */
    boolean setValue(String key, int value) {
        switch (key) {
            case "min" -> minPlayers = Math.max(2, value);
            case "max" -> maxPlayers = Math.max(2, value);
            case "lives" -> lives = Math.max(1, value);
            case "timeout" -> timeout = Math.max(3, value);
            default -> {
                return false;
            }
        }
        return true;
    }

    int minPlayers() {
        return minPlayers;
    }

    int lives() {
        return lives;
    }

    int timeout() {
        return timeout;
    }

    List<Material> palette() {
        return palette;
    }

    void save(ConfigurationSection section) {
        section.set("enabled", enabled);
        section.set("wait", wait);
        section.set("jump", jump);
        section.set("spectator", spectator);
        section.set("pos1", corner1);
        section.set("pos2", corner2);
        section.set("surface", surface);
        section.set("min", minPlayers);
        section.set("max", maxPlayers);
        section.set("lives", lives);
        section.set("timeout", timeout);
        section.set("palette", palette.stream().map(Material::name).toList());
    }

    void load(ConfigurationSection section) {
        enabled = section.getBoolean("enabled");
        wait = section.getLocation("wait");
        jump = section.getLocation("jump");
        spectator = section.getLocation("spectator");
        corner1 = section.getLocation("pos1");
        corner2 = section.getLocation("pos2");
        surface = section.contains("surface") ? section.getInt("surface") : null;
        minPlayers = section.getInt("min", 2);
        maxPlayers = section.getInt("max", 8);
        lives = section.getInt("lives", 3);
        timeout = section.getInt("timeout", 15);
        palette.clear();
        for (String name : section.getStringList("palette")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                palette.add(material);
            }
        }
    }
}
