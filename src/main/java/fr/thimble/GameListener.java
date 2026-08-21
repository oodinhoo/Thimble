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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

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
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location blocked = from.clone();
                blocked.setYaw(to.getYaw());
                blocked.setPitch(to.getPitch());
                event.setTo(blocked);
            }
        }
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
            arena.disconnect(event.getPlayer());
        }
    }

    /** Retour d'un joueur : on lui rend d'abord ce qu'une partie interrompue lui devait. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.restorePending(event.getPlayer());
        for (Arena arena : plugin.arenas()) {
            if (arena.reconnect(event.getPlayer())) {
                return;
            }
        }
    }

    /**
     * Baguette de configuration et choix de la case bonus : on vise, on clique droit.
     * Un seul clic droit declenche deux evenements, un par main : sans ce filtre, chaque clic
     * compterait double.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (plugin.handleWandClick(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        Arena arena = plugin.arenaOf(event.getPlayer());
        if (arena != null) {
            event.setCancelled(true);
            arena.handleBonusClick(event.getPlayer());
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
