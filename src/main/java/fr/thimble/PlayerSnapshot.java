package fr.thimble;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
  * A player's state before they join a game, restored when they leave.
  * Also written to disk on capture: Minecraft saves players every five
  * minutes, so without it a crash mid-game would persist the empty inventory.
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

    void save(ConfigurationSection section) {
        section.set("location", location);
        section.set("gamemode", gameMode.name());
        section.set("inventory", Arrays.asList(inventory));
        section.set("level", level);
        section.set("exp", exp);
        section.set("food", foodLevel);
        section.set("saturation", saturation);
        section.set("allow-flight", allowFlight);
        section.set("flying", flying);
        section.set("invulnerable", invulnerable);
        section.set("effects", new ArrayList<>(effects));
    }

    static PlayerSnapshot load(ConfigurationSection section) {
        List<?> stored = section.getList("inventory", List.of());
        ItemStack[] inventory = stored.stream()
                .map(item -> item instanceof ItemStack stack ? stack : null)
                .toArray(ItemStack[]::new);
        List<PotionEffect> effects = new ArrayList<>();
        for (Object effect : section.getList("effects", List.of())) {
            if (effect instanceof PotionEffect potion) {
                effects.add(potion);
            }
        }
        GameMode mode;
        try {
            mode = GameMode.valueOf(section.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        return new PlayerSnapshot(section.getLocation("location"), mode, inventory,
                section.getInt("level"), (float) section.getDouble("exp"),
                section.getInt("food"), (float) section.getDouble("saturation"),
                section.getBoolean("allow-flight"), section.getBoolean("flying"),
                section.getBoolean("invulnerable"), effects);
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
        if (location != null && location.getWorld() != null) {
            player.teleport(location);
        }
    }
}
