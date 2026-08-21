package fr.thimble;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

final class ThimbleCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBS = List.of("join", "solo", "spectate", "leave", "list", "help");
    private static final List<String> ADMIN_SUBS = List.of("create", "delete", "setwait", "setjump",
            "setspectator", "pos1", "pos2", "setsurface", "set", "palette", "enable", "disable",
            "info", "restore", "forcestart", "forcestop", "wand", "reload");
    private static final List<String> VALUE_KEYS = List.of("min", "max", "lives", "timeout");

    private final ThimblePlugin plugin;

    ThimbleCommand(ThimblePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return help(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String permission = sub.equals("spectate") ? "thimble.spectate" : "thimble.play";
        if (!sub.equals("admin") && !sender.hasPermission(permission)) {
            plugin.send(sender, "general.no-permission");
            return true;
        }
        switch (sub) {
            case "join" -> join(sender, args);
            case "solo" -> solo(sender, args);
            case "spectate" -> spectate(sender, args);
            case "leave" -> leave(sender);
            case "list" -> list(sender);
            case "admin" -> admin(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> help(sender);
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  Joueur
    // ------------------------------------------------------------------

    private void join(CommandSender sender, String[] args) {
        Player player = idlePlayer(sender);
        if (player == null) {
            return;
        }
        Arena arena = arenaArgument(player, args, plugin::bestFreeArena);
        if (arena != null) {
            report(player, arena, arena.join(player));
        }
    }

    private void solo(CommandSender sender, String[] args) {
        Player player = idlePlayer(sender);
        if (player == null) {
            return;
        }
        Arena arena = arenaArgument(player, args, plugin::freeEmptyArena);
        if (arena != null) {
            report(player, arena, arena.startSolo(player));
        }
    }

    private void spectate(CommandSender sender, String[] args) {
        Player player = idlePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.send(player, "general.usage", "usage", "/th spectate <arene>");
            return;
        }
        Arena arena = arenaArgument(player, args, () -> null);
        if (arena != null) {
            report(player, arena, arena.spectate(player));
        }
    }

    /** @return le joueur, s'il en est un et qu'il n'est pas deja dans une arene. */
    private Player idlePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "general.player-only");
            return null;
        }
        if (plugin.arenaOf(player) != null) {
            plugin.send(player, "game.already-in-game");
            return null;
        }
        return player;
    }

    /** L'arene nommee en argument, sinon celle que propose le repli. Signale l'echec. */
    private Arena arenaArgument(Player player, String[] args, Supplier<Arena> fallback) {
        Arena arena = args.length > 1 ? plugin.arena(args[1]) : fallback.get();
        if (arena == null) {
            plugin.send(player, args.length > 1 ? "arena.unknown" : "game.no-free-arena",
                    "arena", args.length > 1 ? args[1] : "");
        }
        return arena;
    }

    private void report(Player player, Arena arena, String error) {
        if (error != null) {
            plugin.send(player, error, "arena", arena.id());
        }
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "general.player-only");
            return;
        }
        Arena arena = plugin.arenaOf(player);
        if (arena == null) {
            plugin.send(player, "game.not-in-game");
            return;
        }
        arena.leave(player, true);
    }

    private void list(CommandSender sender) {
        if (plugin.arenas().isEmpty()) {
            plugin.send(sender, "arena.list-empty");
            return;
        }
        plugin.send(sender, "arena.list-header", "count", String.valueOf(plugin.arenas().size()));
        for (Arena arena : plugin.arenas()) {
            sender.sendMessage(plugin.messageResolved("arena.list-entry",
                    Placeholder.unparsed("arena", arena.id()),
                    Placeholder.unparsed("players", String.valueOf(arena.playerCount())),
                    Placeholder.unparsed("max", String.valueOf(arena.maxPlayers())),
                    plugin.stateTag(arena)));
        }
    }

    private boolean help(CommandSender sender) {
        plugin.send(sender, "help.header");
        sender.sendMessage(plugin.message("help.player"));
        if (sender.hasPermission("thimble.admin")) {
            sender.sendMessage(plugin.message("help.admin"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  Administration
    // ------------------------------------------------------------------

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("thimble.admin")) {
            plugin.send(sender, "general.no-permission");
            return;
        }
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            plugin.reload();
            plugin.send(sender, "general.reloaded");
            return;
        }
        if (args.length < 2) {
            plugin.send(sender, "general.usage", "usage", "/th admin " + sub + " <arene>");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!ThimblePlugin.validId(id)) {
            plugin.send(sender, "arena.invalid-id", "value", args[1]);
            return;
        }
        Arena arena = plugin.arena(id);
        if (sub.equals("create")) {
            if (arena != null) {
                plugin.send(sender, "arena.already-exists", "arena", id);
            } else {
                plugin.createArena(id);
                plugin.send(sender, "arena.created", "arena", id);
            }
            return;
        }
        if (sub.equals("wand")) {
            if (sender instanceof Player player) {
                plugin.startSetup(player, arena != null ? arena : plugin.createArena(id));
            } else {
                plugin.send(sender, "general.player-only");
            }
            return;
        }
        if (arena == null) {
            plugin.send(sender, "arena.unknown", "arena", id);
            return;
        }
        switch (sub) {
            case "delete" -> {
                plugin.deleteArena(arena);
                plugin.send(sender, "arena.deleted", "arena", id);
            }
            case "setwait", "setjump", "setspectator", "pos1", "pos2" -> setLocation(sender, arena, sub);
            case "setsurface" -> setSurface(sender, arena, args);
            case "set" -> setValue(sender, arena, args);
            case "palette" -> palette(sender, arena, args);
            case "enable" -> enable(sender, arena);
            case "disable" -> {
                arena.setEnabled(false);
                plugin.saveArenas();
                plugin.send(sender, "arena.disabled-ok", "arena", id);
            }
            case "info" -> info(sender, arena);
            case "restore" -> {
                arena.restorePool();
                plugin.send(sender, "arena.restored", "arena", id);
            }
            case "forcestart" -> {
                arena.forceStart();
                plugin.send(sender, "arena.started-ok", "arena", id);
            }
            case "forcestop" -> {
                arena.forceStop();
                plugin.send(sender, "arena.stopped-ok", "arena", id);
            }
            default -> help(sender);
        }
    }

    private void setLocation(CommandSender sender, Arena arena, String what) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "general.player-only");
            return;
        }
        if (arena.state() == Arena.State.PLAYING) {
            plugin.send(sender, "arena.busy", "arena", arena.id());
            return;
        }
        boolean corner = what.startsWith("pos");
        Location location = corner ? player.getLocation().getBlock().getLocation() : player.getLocation();
        arena.setLocation(what, location);
        plugin.saveArenas();
        if (corner) {
            plugin.send(sender, "arena.corner-set",
                    "corner", what,
                    "pos", location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ(),
                    "water", arena.hasPool() ? String.valueOf(arena.waterLeft()) : "-",
                    "y", arena.hasPool() ? String.valueOf(arena.surfaceY()) : "-");
        } else {
            plugin.send(sender, "arena.position-set", "what", what, "arena", arena.id());
        }
    }

    private void setSurface(CommandSender sender, Arena arena, String[] args) {
        Integer y;
        if (args.length > 2) {
            y = parseInt(sender, args[2]);
            if (y == null) {
                return;
            }
        } else if (sender instanceof Player player) {
            y = player.getLocation().getBlockY();
        } else {
            plugin.send(sender, "general.player-only");
            return;
        }
        arena.setSurface(y);
        plugin.saveArenas();
        plugin.send(sender, "arena.surface-set", "y", String.valueOf(y),
                "water", arena.hasPool() ? String.valueOf(arena.waterLeft()) : "-");
    }

    private void setValue(CommandSender sender, Arena arena, String[] args) {
        String usage = "/th admin set <arene> <min|max|lives|timeout> <valeur>";
        if (args.length < 4) {
            plugin.send(sender, "general.usage", "usage", usage);
            return;
        }
        Integer value = parseInt(sender, args[3]);
        if (value == null) {
            return;
        }
        String key = args[2].toLowerCase(Locale.ROOT);
        if (!arena.setValue(key, value)) {
            plugin.send(sender, "general.usage", "usage", usage);
            return;
        }
        plugin.saveArenas();
        plugin.send(sender, "arena.value-set", "key", key, "value", String.valueOf(value), "arena", arena.id());
    }

    private void palette(CommandSender sender, Arena arena, String[] args) {
        String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "clear" -> arena.palette().clear();
            case "add", "remove" -> {
                if (args.length < 4) {
                    plugin.send(sender, "general.usage", "usage", "/th admin palette <arene> " + action + " <bloc>");
                    return;
                }
                Material material = Material.matchMaterial(args[3]);
                if (material == null || !material.isBlock()) {
                    plugin.send(sender, "arena.unknown-material", "value", args[3]);
                    return;
                }
                if (action.equals("add")) {
                    arena.palette().add(material);
                } else {
                    arena.palette().remove(material);
                }
            }
            default -> { }
        }
        if (!action.equals("list")) {
            plugin.saveArenas();
        }
        plugin.send(sender, "arena.palette-updated", "arena", arena.id(), "palette", paletteNames(arena));
    }

    private void enable(CommandSender sender, Arena arena) {
        List<String> missing = arena.missing();
        if (!missing.isEmpty()) {
            plugin.send(sender, "arena.incomplete", "missing", String.join(", ", missing));
            return;
        }
        arena.setEnabled(true);
        plugin.saveArenas();
        plugin.send(sender, "arena.enabled-ok", "arena", arena.id());
    }

    private void info(CommandSender sender, Arena arena) {
        List<String> missing = arena.missing();
        sender.sendMessage(plugin.messageResolved("arena.info",
                Placeholder.unparsed("arena", arena.id()),
                Placeholder.unparsed("min", String.valueOf(arena.minPlayers())),
                Placeholder.unparsed("max", String.valueOf(arena.maxPlayers())),
                Placeholder.unparsed("lives", String.valueOf(arena.lives())),
                Placeholder.unparsed("timeout", String.valueOf(arena.timeout())),
                Placeholder.unparsed("water", arena.hasPool() ? String.valueOf(arena.waterLeft()) : "-"),
                Placeholder.unparsed("palette", paletteNames(arena)),
                plugin.stateTag(arena)));
        if (!missing.isEmpty()) {
            plugin.send(sender, "arena.incomplete", "missing", String.join(", ", missing));
        }
    }

    private String paletteNames(Arena arena) {
        return arena.palette().isEmpty()
                ? "(defaut)"
                : String.join(", ", arena.palette().stream().map(Material::name).toList());
    }

    private Integer parseInt(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            plugin.send(sender, "general.invalid-number", "value", raw);
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER_SUBS);
            if (sender.hasPermission("thimble.admin")) {
                options.add("admin");
            }
        } else if ((args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("spectate")
                || args[0].equalsIgnoreCase("solo")) && args.length == 2) {
            plugin.arenas().forEach(arena -> options.add(arena.id()));
        } else if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("thimble.admin")) {
            switch (args.length) {
                case 2 -> options.addAll(ADMIN_SUBS);
                case 3 -> plugin.arenas().forEach(arena -> options.add(arena.id()));
                case 4 -> {
                    if (args[1].equalsIgnoreCase("set")) {
                        options.addAll(VALUE_KEYS);
                    } else if (args[1].equalsIgnoreCase("palette")) {
                        options.addAll(List.of("add", "remove", "clear", "list"));
                    }
                }
                default -> { }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
