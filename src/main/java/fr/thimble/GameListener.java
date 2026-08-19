package fr.thimble;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class GameListener implements Listener {

    private final ThimblePlugin plugin;

    GameListener(ThimblePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Arena arena = plugin.arenaOf(event.getPlayer());
        if (arena == null) {
            return;
        }
        if (arena.frozen(event.getPlayer())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                Location blocked = from.clone();
                blocked.setYaw(to.getYaw());
                blocked.setPitch(to.getPitch());
                event.setTo(blocked);
            }
            return;
        }
        arena.handleMove(event.getPlayer());
    }

    /** Personne ne meurt : les vies sont gerees par le plugin. */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && plugin.arenaOf(player) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Arena arena = plugin.arenaOf(event.getPlayer());
        if (arena != null) {
            arena.leave(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        cancelInArena(event.getPlayer(), event);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        cancelInArena(event.getPlayer(), event);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        cancelInArena(event.getPlayer(), event);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelInArena(player, event);
        }
    }

    @EventHandler
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelInArena(player, event);
        }
    }

    private void cancelInArena(Player player, org.bukkit.event.Cancellable event) {
        if (plugin.arenaOf(player) != null) {
            event.setCancelled(true);
        }
    }
}
