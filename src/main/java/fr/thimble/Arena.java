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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Une arene : sa configuration, et la partie qui s'y deroule.
 * Tout est pilote par {@link #tick()}, appele chaque tick par le plugin.
 */
final class Arena {

    enum State { DISABLED, WAITING, STARTING, PLAYING, ENDING }

    /** Un joueur dans la partie en cours. */
    private static final class Seat {
        PlayerSnapshot snapshot;
        Material block;
        int lives;
        boolean alive = true;
        /** Ticks restants avant l'elimination d'un joueur deconnecte ; 0 = connecte. */
        int grace;
        int hits;
        int thimbles;

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
    private final Map<UUID, PlayerSnapshot> spectators = new LinkedHashMap<>();
    private final List<UUID> order = new ArrayList<>();
    /** Joueurs elimines, dans l'ordre : lu a l'envers, c'est le classement. */
    private final List<UUID> eliminated = new ArrayList<>();
    private final Map<Block, BlockData> changed = new LinkedHashMap<>();
    private UUID jumper;
    private UUID bonusPicker;
    private int bonusTicks;
    private int index;
    private int round;
    private int ticks;
    private int freeze;
    private int hudTick;
    private int pauseTicks;
    private Runnable pending;
    private boolean falling;
    private boolean solo;

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

    /** Les joueurs qui voient la partie : participants connectes et spectateurs. */
    private List<Player> viewers() {
        List<Player> viewers = new ArrayList<>();
        for (UUID uuid : seats.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                viewers.add(player);
            }
        }
        for (UUID uuid : spectators.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                viewers.add(player);
            }
        }
        return viewers;
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
            return plugin.waitAsSpectator() ? spectate(player) : "game.in-progress";
        }
        if (seats.size() >= maxPlayers) {
            return "game.full";
        }
        Seat seat = new Seat(PlayerSnapshot.capture(player));
        seats.put(player.getUniqueId(), seat);
        plugin.remember(player, seat.snapshot);
        plugin.track(player, this);
        prepare(player, GameMode.ADVENTURE, wait);
        broadcast("game.joined", "player", player.getName(),
                "players", String.valueOf(seats.size()), "max", String.valueOf(maxPlayers));
        return null;
    }

    /**
     * Partie solo : on remplit la piscine tout seul, sans decompte ni adversaire.
     * Elle se termine a court de vies, ou en victoire si la piscine est entierement remplie.
     *
     * @return la cle du message d'erreur, ou null si la partie a demarre.
     */
    String startSolo(Player player) {
        if (!enabled) {
            return "arena.disabled";
        }
        if (state != State.WAITING || !seats.isEmpty()) {
            return "game.solo-unavailable";
        }
        String error = join(player);
        if (error != null) {
            return error;
        }
        start();
        return null;
    }

    /** @return la cle du message d'erreur, ou null si le joueur observe desormais la partie. */
    String spectate(Player player) {
        if (!enabled) {
            return "arena.disabled";
        }
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        spectators.put(player.getUniqueId(), snapshot);
        plugin.remember(player, snapshot);
        plugin.track(player, this);
        prepare(player, GameMode.SPECTATOR, spectator != null ? spectator : wait);
        plugin.send(player, "game.spectating", "arena", id);
        return null;
    }

    private void prepare(Player player, GameMode mode, Location destination) {
        player.getInventory().clear();
        player.setGameMode(mode);
        player.setInvulnerable(true);
        player.setAllowFlight(mode == GameMode.SPECTATOR);
        player.setFlying(false);
        player.setFallDistance(0);
        player.teleport(destination);
    }

    void leave(Player player, boolean announce) {
        UUID uuid = player.getUniqueId();
        if (!seats.containsKey(uuid) && !spectators.containsKey(uuid)) {
            return;
        }
        plugin.untrack(uuid);
        plugin.hud().clear(player);
        if (uuid.equals(bonusPicker)) {
            clearBonus(player);
        }
        PlayerSnapshot watching = spectators.remove(uuid);
        if (watching != null) {
            watching.restore(player);
            plugin.forget(uuid);
            return;
        }
        Seat seat = seats.remove(uuid);
        if (seat == null) {
            return;
        }
        order.remove(uuid);
        seat.snapshot.restore(player);
        plugin.forget(uuid);
        if (announce) {
            broadcast("game.left", "player", player.getName(),
                    "players", String.valueOf(seats.size()), "max", String.valueOf(maxPlayers));
        }
        if (state == State.PLAYING) {
            if (uuid.equals(jumper)) {
                jumper = null;
                nextTurn();
            } else if (gameOver()) {
                end();
            }
        } else if (state == State.STARTING && seats.size() < minPlayers) {
            state = State.WAITING;
            broadcast("game.countdown-cancelled");
            plugin.hud().hideBar(this, viewers());
        }
    }

    /**
     * Deconnexion en pleine partie : la place est gardee pendant le delai de grace.
     * L'inventaire est rendu tout de suite (l'evenement de deconnexion precede la sauvegarde).
     */
    void disconnect(Player player) {
        UUID uuid = player.getUniqueId();
        Seat seat = seats.get(uuid);
        if (seat == null || state != State.PLAYING || !seat.alive || plugin.rejoinGraceSeconds() <= 0) {
            leave(player, true);
            return;
        }
        if (uuid.equals(bonusPicker)) {
            clearBonus(player);
        }
        seat.snapshot.restore(player);
        seat.grace = plugin.rejoinGraceSeconds() * 20;
        broadcast("game.disconnected", "player", player.getName(),
                "time", String.valueOf(plugin.rejoinGraceSeconds()));
        if (uuid.equals(jumper)) {
            jumper = null;
            broadcast("game.quit-during-turn", "player", player.getName());
            nextTurn();
        }
    }

    /** @return true si le joueur a repris sa place. */
    boolean reconnect(Player player) {
        Seat seat = seats.get(player.getUniqueId());
        if (seat == null || seat.grace <= 0) {
            return false;
        }
        seat.grace = 0;
        seat.snapshot = PlayerSnapshot.capture(player);
        plugin.remember(player, seat.snapshot);
        prepare(player, seat.alive ? GameMode.ADVENTURE : GameMode.SPECTATOR,
                spectator != null ? spectator : wait);
        broadcast("game.reconnected", "player", player.getName());
        return true;
    }

    /** Arret propre : tout le monde est restaure, la piscine aussi. */
    void shutdown() {
        viewers().forEach(player -> plugin.hud().clear(player));
        for (UUID uuid : new ArrayList<>(seats.keySet())) {
            Seat seat = seats.remove(uuid);
            plugin.untrack(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && seat != null && seat.grace == 0) {
                seat.snapshot.restore(player);
                plugin.forget(uuid);
            }
        }
        for (UUID uuid : new ArrayList<>(spectators.keySet())) {
            PlayerSnapshot snapshot = spectators.remove(uuid);
            plugin.untrack(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && snapshot != null) {
                snapshot.restore(player);
                plugin.forget(uuid);
            }
        }
        resetGame();
    }

    /** Remet la piscine et l'etat de partie a zero, sans toucher aux joueurs. */
    private void resetGame() {
        restorePool();
        order.clear();
        eliminated.clear();
        jumper = null;
        bonusPicker = null;
        pending = null;
        pauseTicks = 0;
        solo = false;
        state = State.WAITING;
    }

    /** Remet tout le monde au lobby d'attente pour une nouvelle partie, sans quitter l'arene. */
    private void requeue() {
        resetGame();
        for (UUID uuid : new ArrayList<>(seats.keySet())) {
            Seat seat = seats.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || seat.grace > 0) {
                seats.remove(uuid);
                plugin.untrack(uuid);
                continue;
            }
            seat.alive = true;
            seat.lives = lives;
            seat.hits = 0;
            seat.thimbles = 0;
            prepare(player, GameMode.ADVENTURE, wait);
        }
        // Un spectateur arrive en cours de partie entre vraiment en jeu a la manche suivante,
        // sinon il resterait spectateur indefiniment.
        for (UUID uuid : new ArrayList<>(spectators.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || seats.size() >= maxPlayers) {
                continue;
            }
            spectators.remove(uuid);
            seats.put(uuid, new Seat(PlayerSnapshot.capture(player)));
            prepare(player, GameMode.ADVENTURE, wait);
        }
        broadcast(seats.size() >= minPlayers ? "game.replay" : "game.replay-waiting",
                "players", String.valueOf(seats.size()), "min", String.valueOf(minPlayers));
    }

    void forceStop() {
        if (state == State.PLAYING || state == State.STARTING) {
            broadcast("game.stopped");
        }
        shutdown();
    }

    void forceStart() {
        if (state == State.WAITING && !seats.isEmpty()) {
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
                    plugin.hud().hideBar(this, viewers());
                    return;
                }
                if (seats.size() >= maxPlayers) {
                    ticks = Math.min(ticks, plugin.countdownFullSeconds() * 20);
                }
                if (ticks % 20 == 0) {
                    int seconds = ticks / 20;
                    plugin.hud().bar(this, viewers(), "bossbar.countdown",
                            ticks / (float) (plugin.countdownSeconds() * 20), BossBar.Color.BLUE,
                            "time", String.valueOf(seconds));
                    if (seconds == 30 || seconds == 20 || seconds == 15 || seconds == 10
                            || (seconds > 0 && seconds <= 5)) {
                        broadcast("game.countdown", "time", String.valueOf(seconds));
                        plugin.hud().sound(viewers(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f);
                    }
                }
                if (--ticks <= 0) {
                    start();
                }
            }
            case PLAYING -> tickTurn();
            case ENDING -> {
                if (--ticks <= 0) {
                    // Les joueurs restent sur place pour la partie suivante : sans ca, il faut
                    // retaper /th join a chaque manche. Le solo, lui, rend la main.
                    if (solo || seats.isEmpty()) {
                        shutdown();
                    } else {
                        requeue();
                    }
                }
            }
            default -> { }
        }
        if (++hudTick % 20 == 0) {
            refreshHud();
        }
    }

    private void tickTurn() {
        tickGrace();
        if (state != State.PLAYING) {
            // tickGrace() a pu terminer la partie : sans ce retour, le tour continuait
            // et pouvait encore poser un bloc.
            return;
        }
        if (pending != null) {
            if (--pauseTicks <= 0) {
                Runnable action = pending;
                pending = null;
                action.run();
            }
            return;
        }
        if (bonusPicker != null) {
            if (bonusTicks % 10 == 0) {
                plugin.hud().bar(this, viewers(), "bossbar.bonus",
                        bonusTicks / (float) Math.max(1, plugin.bonusSelectSeconds() * 20),
                        BossBar.Color.PURPLE, "time", String.valueOf(Math.max(0, bonusTicks / 20)));
            }
            if (--bonusTicks <= 0) {
                Player picker = Bukkit.getPlayer(bonusPicker);
                fillBonus(randomWater(), picker, true);
                endTurn(picker);
            }
            return;
        }
        if (freeze > 0) {
            freeze--;
            return;
        }
        if (jumper == null) {
            return;
        }
        Player player = Bukkit.getPlayer(jumper);
        if (player != null) {
            if (falling) {
                plugin.hud().trail(player.getLocation(), viewers());
            }
            // La resolution est faite ici, et non sur PlayerMoveEvent : un joueur qui atterrit
            // sur un bloc et reste immobile n'emet plus aucun mouvement.
            if (checkLanding(player)) {
                return;
            }
        }
        if (ticks % 10 == 0) {
            boolean urgent = ticks <= 100;
            plugin.hud().bar(this, viewers(), urgent ? "bossbar.turn-urgent" : "bossbar.turn",
                    ticks / (float) (timeout * 20),
                    urgent ? BossBar.Color.RED : BossBar.Color.YELLOW,
                    "player", nameOf(jumper),
                    "time", String.valueOf(Math.max(0, ticks / 20)));
        }
        if (--ticks <= 0) {
            miss("game.timeout");
        }
    }

    /** Met la partie en pause avant d'enchainer, pour laisser voir ce qui vient de se passer. */
    private void pause(int ticksToWait, Runnable action) {
        pauseTicks = ticksToWait;
        pending = action;
    }

    /** Decompte du delai de grace des joueurs deconnectes. */
    private void tickGrace() {
        for (UUID uuid : new ArrayList<>(seats.keySet())) {
            Seat seat = seats.get(uuid);
            if (seat == null || seat.grace <= 0 || --seat.grace > 0) {
                continue;
            }
            seats.remove(uuid);
            order.remove(uuid);
            plugin.untrack(uuid);
            broadcast("game.rejoin-expired", "player", nameOf(uuid),
                    "remaining", String.valueOf(aliveCount()));
        }
        if (state == State.PLAYING && gameOver()) {
            end();
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
        solo = order.size() == 1;
        round = 1;
        index = -1;
        broadcast(solo ? "game.solo-started" : "game.started", "lives", String.valueOf(lives));
        broadcast("game.round", "round", "1");
        plugin.hud().title(viewers(), "title.start", "title.start-sub");
        plugin.hud().sound(viewers(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f);
        nextTurn();
    }

    private void nextTurn() {
        if (state != State.PLAYING) {
            return;
        }
        if (gameOver()) {
            end();
            return;
        }
        if (waterLeft() == 0) {
            if (solo || !plugin.refillWhenFull()) {
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
        freeze = plugin.freezeSeconds() * 20;
        ticks = timeout * 20;
        player.teleport(jump);
        player.setFallDistance(0);
        plugin.hud().title(player, "title.your-turn", "title.your-turn-sub");
        plugin.hud().sound(List.of(player), Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
    }

    /** @return true si le saut vient d'etre resolu. */
    private boolean checkLanding(Player player) {
        Location location = player.getLocation();
        if (!falling) {
            // Le saut ne commence que lorsque le joueur a vraiment quitte le plongeoir.
            if (location.getY() > jump.getY() - 1.0) {
                return false;
            }
            falling = true;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        // Fenetre de detection d'au moins 4 blocs : un joueur tombe vite, et les deux coins
        // peuvent avoir ete poses au meme niveau (piscine "plate" du point de vue de la region).
        if (inPool(x, z) && y <= surfaceY() && y >= Math.min(floorY(), surfaceY() - 3)) {
            Block target = world().getBlockAt(x, surfaceY(), z);
            if (target.getType() == Material.WATER) {
                hit(player, target);
                return true;
            }
        }
        if (onGround(player) || location.getY() < floorY() - 5
                || location.getY() < world().getMinHeight() + 1) {
            miss("game.miss");
            return true;
        }
        return false;
    }

    /**
     * Atterrissage sur un bloc, teste sous les quatre coins de la hitbox : un joueur pose sur le
     * bord d'un bloc a un centre qui surplombe la colonne voisine, et le raté passait inapercu.
     * {@code Player#isOnGround()} n'est pas une option, il est renseigne par le client.
     */
    private boolean onGround(Player player) {
        BoundingBox box = player.getBoundingBox();
        int y = (int) Math.floor(box.getMinY() - 0.05);
        for (double x : new double[] {box.getMinX(), box.getMaxX()}) {
            for (double z : new double[] {box.getMinZ(), box.getMaxZ()}) {
                if (world().getBlockAt((int) Math.floor(x), y, (int) Math.floor(z))
                        .getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Joueur immobilise : sur le plongeoir avant son saut, ou en vol au-dessus de la piscine. */
    boolean frozen(Player player) {
        UUID uuid = player.getUniqueId();
        return (freeze > 0 && uuid.equals(jumper)) || uuid.equals(bonusPicker);
    }

    private void hit(Player player, Block target) {
        Seat seat = seats.get(player.getUniqueId());
        jumper = null;
        boolean thimble = isolated(target.getX(), target.getZ());
        seat.hits++;
        if (thimble) {
            seat.thimbles++;
        }
        place(target, seat.block);
        broadcast(thimble ? "game.thimble" : "game.hit", "player", player.getName(),
                "water", String.valueOf(waterLeft()));
        if (thimble) {
            plugin.hud().title(viewers(), "title.thimble", "title.thimble-sub", "player", player.getName());
            plugin.hud().sound(viewers(), Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
            plugin.hud().burst(target.getLocation().add(0.5, 0.5, 0.5), viewers());
            if (askBonus(player, seat)) {
                return;
            }
        } else {
            plugin.hud().title(player, "title.hit", null);
            plugin.hud().sound(List.of(player), Sound.ENTITY_PLAYER_SPLASH, 1f);
        }
        // Le joueur reste un instant dans l'eau : on voit ou il est tombe avant qu'il disparaisse.
        pause(plugin.splashPauseSeconds() * 20, () -> endTurn(player));
    }

    /**
     * Bonus du de a coudre : le joueur choisit une case d'eau supplementaire en visant puis
     * en faisant un clic droit. Sans choix a temps, une case est tiree au sort.
     *
     * @return true si la partie attend le choix du joueur.
     */
    private boolean askBonus(Player player, Seat seat) {
        if (plugin.bonusSelectSeconds() <= 0 || waterLeft() == 0) {
            fillBonus(randomWater(), player, true);
            return false;
        }
        bonusPicker = player.getUniqueId();
        bonusTicks = plugin.bonusSelectSeconds() * 20;
        player.getInventory().setItem(0, new ItemStack(seat.block));
        player.getInventory().setHeldItemSlot(0);
        // Vue plongeante sur la piscine, immobile : seul le regard bouge (voir frozen()).
        player.setAllowFlight(true);
        player.setFlying(true);
        player.teleport(abovePool());
        plugin.hud().title(player, "title.bonus", "title.bonus-sub");
        return true;
    }

    /** Point de vue au-dessus du centre de la piscine, regard vers le bas. */
    private Location abovePool() {
        double x = (Math.min(corner1.getBlockX(), corner2.getBlockX())
                + Math.max(corner1.getBlockX(), corner2.getBlockX())) / 2.0 + 0.5;
        double z = (Math.min(corner1.getBlockZ(), corner2.getBlockZ())
                + Math.max(corner1.getBlockZ(), corner2.getBlockZ())) / 2.0 + 0.5;
        Location view = new Location(world(), x, surfaceY() + plugin.bonusViewHeight(), z);
        view.setPitch(90f);
        return view;
    }

    /** Clic droit du joueur qui doit choisir sa case bonus. */
    void handleBonusClick(Player player) {
        if (bonusPicker == null || !bonusPicker.equals(player.getUniqueId())) {
            return;
        }
        Block target = ThimblePlugin.targetBlock(player);
        if (target == null || target.getY() != surfaceY() || !inPool(target.getX(), target.getZ())) {
            plugin.send(player, "game.bonus-invalid");
            return;
        }
        if (target.getType() == Material.WATER) {
            fillBonus(target, player, false);
        } else if (changed.containsKey(target)) {
            // Sabotage : rouvrir une case bouchee pendant la partie. Seuls les blocs poses par
            // le jeu sont concernes, le fond et les bords de la piscine restent intouchables.
            target.setBlockData(changed.remove(target), false);
            clearBonus(player);
            broadcast("game.bonus-open", "player", player.getName());
        } else {
            plugin.send(player, "game.bonus-invalid");
            return;
        }
        endTurn(player);
    }

    private void fillBonus(Block target, Player player, boolean random) {
        Seat seat = player == null ? null : seats.get(player.getUniqueId());
        if (target != null && seat != null) {
            place(target, seat.block);
            broadcast(random ? "game.bonus-random" : "game.bonus-block");
        }
        clearBonus(player);
    }

    /** Fin du choix bonus : le joueur redescend de sa vue plongeante. */
    private void clearBonus(Player player) {
        bonusPicker = null;
        bonusTicks = 0;
        if (player != null) {
            player.getInventory().clear();
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    /** Fin de tour : retour aux gradins, puis joueur suivant. Un seul endroit qui enchaine. */
    private void endTurn(Player player) {
        if (player != null) {
            park(player);
        }
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
                eliminated.add(uuid);
                if (player != null) {
                    player.setGameMode(GameMode.SPECTATOR);
                    plugin.hud().title(player, "title.eliminated", null);
                }
                broadcast("game.eliminated", "player", name, "remaining", String.valueOf(aliveCount()));
                plugin.hud().sound(viewers(), Sound.ENTITY_WITHER_HURT, 1f);
            } else {
                broadcast(messageKey, "player", name, "lives", String.valueOf(seat.lives));
                if (player != null) {
                    plugin.hud().titleResolved(player, "title.miss", "title.miss-sub",
                            Placeholder.component("hearts", Hud.hearts(seat.lives, lives)));
                    plugin.hud().sound(List.of(player), Sound.ENTITY_ITEM_BREAK, 0.8f);
                }
            }
        }
        endTurn(seat != null && seat.alive ? player : null);
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
        plugin.hud().hideBar(this, viewers());
        if (solo) {
            endSolo();
            return;
        }
        if (winner == null) {
            broadcast("game.draw");
            plugin.hud().title(viewers(), "title.draw", null);
        } else {
            String name = nameOf(winner);
            broadcast("game.win", "player", name);
            plugin.hud().title(viewers(), "title.win", "title.win-sub", "player", name);
            plugin.hud().sound(viewers(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        }
        podium(winner);
    }

    /** Classement de fin : le vainqueur, puis les elimines du dernier au premier. */
    private void podium(UUID winner) {
        List<UUID> ranking = new ArrayList<>();
        if (winner != null) {
            ranking.add(winner);
        }
        for (int i = eliminated.size() - 1; i >= 0; i--) {
            ranking.add(eliminated.get(i));
        }
        if (ranking.isEmpty()) {
            return;
        }
        broadcast("game.podium-header");
        for (int rank = 0; rank < ranking.size(); rank++) {
            Seat seat = seats.get(ranking.get(rank));
            if (seat == null) {
                continue;
            }
            broadcast("game.podium-" + Math.min(rank + 1, 4),
                    "rank", String.valueOf(rank + 1),
                    "player", nameOf(ranking.get(rank)),
                    "hits", String.valueOf(seat.hits),
                    "thimbles", String.valueOf(seat.thimbles));
        }
    }

    /** Bilan d'une partie solo : cases remplies et des a coudre reussis. */
    private void endSolo() {
        UUID uuid = order.isEmpty() ? null : order.get(0);
        Seat seat = uuid == null ? null : seats.get(uuid);
        if (seat == null) {
            return;
        }
        // Parfait = la piscine est pleine et le joueur tient encore : une partie interrompue
        // (deconnexion, arret force) ne doit pas etre annoncee comme un sans-faute.
        boolean perfect = seat.alive && waterLeft() == 0;
        broadcast(perfect ? "game.solo-perfect" : "game.solo-over",
                "player", nameOf(uuid),
                "hits", String.valueOf(seat.hits),
                "thimbles", String.valueOf(seat.thimbles));
        plugin.hud().title(viewers(), perfect ? "title.win" : "title.eliminated",
                perfect ? "title.win-sub" : null, "player", nameOf(uuid));
        plugin.hud().sound(viewers(),
                perfect ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_HURT, 1f);
    }

    private String nameOf(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : "?";
    }

    /** Tableau de score et barre d'action, rafraichis une fois par seconde. */
    private void refreshHud() {
        List<Player> viewers = viewers();
        if (viewers.isEmpty()) {
            return;
        }
        int water = state == State.PLAYING ? waterLeft() : 0;
        List<Component> lines = new ArrayList<>();
        if (state == State.PLAYING) {
            for (UUID uuid : order) {
                Seat seat = seats.get(uuid);
                if (seat == null) {
                    continue;
                }
                String key = !seat.alive ? "scoreboard.player-out"
                        : seat.grace > 0 ? "scoreboard.player-away" : "scoreboard.player";
                lines.add(plugin.messageResolved(key,
                        Placeholder.unparsed("player", nameOf(uuid)),
                        Placeholder.unparsed("cursor", uuid.equals(jumper) ? "▶" : "·"),
                        Placeholder.component("square", Hud.square(seat.block)),
                        Placeholder.component("hearts", Hud.hearts(seat.lives, lives))));
            }
            lines.add(plugin.message("scoreboard.separator"));
            lines.add(plugin.message("scoreboard.summary",
                    "round", String.valueOf(round),
                    "water", String.valueOf(water),
                    "total", String.valueOf(poolSize())));
            lines.add(Hud.gauge(water, poolSize()));
        } else {
            lines.add(plugin.message("scoreboard.waiting",
                    "players", String.valueOf(seats.size()), "min", String.valueOf(minPlayers)));
            if (state == State.STARTING) {
                lines.add(plugin.message("scoreboard.countdown", "time", String.valueOf(ticks / 20)));
            }
        }
        lines.add(plugin.message("scoreboard.arena", "arena", id));
        plugin.hud().board(this, lines, viewers);
        refreshActionBar(viewers, water);
    }

    /** La consigne du moment : elle remplace les rappels que le chat repetait a chaque tour. */
    private void refreshActionBar(List<Player> viewers, int water) {
        if (state != State.PLAYING) {
            plugin.hud().actionBar(viewers, "actionbar.waiting",
                    "players", String.valueOf(seats.size()), "min", String.valueOf(minPlayers));
            return;
        }
        // Les spectateurs lisent tous la meme phrase : elle n'est composee qu'une fois.
        List<Player> watchers = new ArrayList<>(viewers.size());
        for (Player player : viewers) {
            UUID uuid = player.getUniqueId();
            if (uuid.equals(bonusPicker)) {
                plugin.hud().actionBar(List.of(player), "actionbar.bonus",
                        "time", String.valueOf(Math.max(0, bonusTicks / 20)));
            } else if (uuid.equals(jumper)) {
                plugin.hud().actionBar(List.of(player), "actionbar.your-turn",
                        "water", String.valueOf(water));
            } else {
                watchers.add(player);
            }
        }
        plugin.hud().actionBar(watchers, "actionbar.watching",
                "player", jumper == null ? "-" : nameOf(jumper), "water", String.valueOf(water));
    }

    /** Nombre de cases de la surface de jeu, remplies ou non. */
    private int poolSize() {
        if (!hasPool()) {
            return 0;
        }
        int width = Math.abs(corner1.getBlockX() - corner2.getBlockX()) + 1;
        int depth = Math.abs(corner1.getBlockZ() - corner2.getBlockZ()) + 1;
        return width * depth;
    }

    private void park(Player player) {
        player.setFallDistance(0);
        player.teleport(spectator != null ? spectator : wait);
    }

    /** En solo la partie continue tant que le joueur est en vie ; a plusieurs, jusqu'au dernier. */
    private boolean gameOver() {
        return aliveCount() <= (solo ? 0 : 1);
    }

    private int aliveCount() {
        return (int) seats.values().stream().filter(seat -> seat.alive).count();
    }

    /** Les lignes de jeu vont au chat sans prefixe : dans l'arene, personne n'en a besoin. */
    private void broadcast(String key, String... placeholders) {
        Component line = plugin.message(key, placeholders);
        viewers().forEach(player -> player.sendMessage(line));
    }

    // ------------------------------------------------------------------
    //  Piscine
    // ------------------------------------------------------------------

    private World world() {
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

    /** Les cases encore en eau a la surface de la piscine. */
    private List<Block> waterBlocks() {
        List<Block> water = new ArrayList<>();
        if (!hasPool()) {
            return water;
        }
        for (int x = Math.min(corner1.getBlockX(), corner2.getBlockX()); x <= Math.max(corner1.getBlockX(), corner2.getBlockX()); x++) {
            for (int z = Math.min(corner1.getBlockZ(), corner2.getBlockZ()); z <= Math.max(corner1.getBlockZ(), corner2.getBlockZ()); z++) {
                Block block = world().getBlockAt(x, surfaceY(), z);
                if (block.getType() == Material.WATER) {
                    water.add(block);
                }
            }
        }
        return water;
    }

    int waterLeft() {
        return waterBlocks().size();
    }

    private Block randomWater() {
        List<Block> water = waterBlocks();
        return water.isEmpty() ? null : water.get(ThreadLocalRandom.current().nextInt(water.size()));
    }

    private void place(Block block, Material material) {
        changed.putIfAbsent(block, block.getBlockData());
        block.setType(material, false);
    }

    /**
     * Remet la piscine en eau. Sans journal en memoire — apres un redemarrage ou un crash —
     * la surface est repeinte a partir de la geometrie, sinon l'arene resterait bouchee.
     */
    void restorePool() {
        if (!changed.isEmpty()) {
            changed.forEach((block, data) -> block.setBlockData(data, false));
            changed.clear();
            return;
        }
        if (!hasPool()) {
            return;
        }
        for (int x = Math.min(corner1.getBlockX(), corner2.getBlockX()); x <= Math.max(corner1.getBlockX(), corner2.getBlockX()); x++) {
            for (int z = Math.min(corner1.getBlockZ(), corner2.getBlockZ()); z <= Math.max(corner1.getBlockZ(), corner2.getBlockZ()); z++) {
                Block block = world().getBlockAt(x, surfaceY(), z);
                if (block.getType() != Material.WATER) {
                    block.setType(Material.WATER, false);
                }
            }
        }
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
        wait = section.getLocation("wait");
        jump = section.getLocation("jump");
        spectator = section.getLocation("spectator");
        corner1 = section.getLocation("pos1");
        corner2 = section.getLocation("pos2");
        surface = section.contains("surface") ? section.getInt("surface") : null;
        setValue("min", section.getInt("min", 2));
        setValue("max", section.getInt("max", 8));
        setValue("lives", section.getInt("lives", 3));
        setValue("timeout", section.getInt("timeout", 15));
        palette.clear();
        for (String name : section.getStringList("palette")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                palette.add(material);
            }
        }
        // Un fichier edite a la main peut declarer une arene active mais incomplete :
        // elle planterait au premier /th join.
        enabled = section.getBoolean("enabled") && missing().isEmpty();
    }
}
