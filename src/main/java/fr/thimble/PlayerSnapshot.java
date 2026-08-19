package fr.thimble;

import java.util.Collection;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * Etat d'un joueur avant qu'il entre en partie, restaure a sa sortie.
 * Rien n'est persiste sur disque : en cas de crash serveur l'inventaire du joueur
 * est celui de sa derniere sauvegarde vanilla.
 */
record PlayerSnapshot(Location location, GameMode gameMode, ItemStack[] inventory, int level, float exp,
        int foodLevel, float saturation, boolean allowFlight, boolean flying, boolean invulnerable,
        Collection<PotionEffect> effects) {

    static PlayerSnapshot capture(Player player) {
        return new PlayerSnapshot(
                player.getLocation().clone(),
                player.getGameMode(),
                player.getInventory().getContents().clone(),
                player.getLevel(),
                player.getExp(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getAllowFlight(),
                player.isFlying(),
                player.isInvulnerable(),
                List.copyOf(player.getActivePotionEffects()));
    }

    void restore(Player player) {
        player.getInventory().setContents(inventory);
        player.setGameMode(gameMode);
        player.setLevel(level);
        player.setExp(exp);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setAllowFlight(allowFlight);
        player.setFlying(flying && allowFlight);
        player.setInvulnerable(invulnerable);
        player.setFireTicks(0);
        player.setFallDistance(0);
        for (PotionEffect active : player.getActivePotionEffects()) {
            player.removePotionEffect(active.getType());
        }
        player.addPotionEffects(effects);
        player.teleport(location);
    }
}
