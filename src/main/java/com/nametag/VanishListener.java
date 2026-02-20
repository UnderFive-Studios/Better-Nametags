package com.nametag;

import de.myzelyam.api.vanish.PlayerHideEvent;
import de.myzelyam.api.vanish.PlayerShowEvent;
import de.myzelyam.api.vanish.VanishAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Dedicated listener for SuperVanish/PremiumVanish events.
 * 
 * This listener is only registered if SuperVanish or PremiumVanish is present.
 * It directly listens to vanish events for INSTANT nametag visibility management.
 */
public class VanishListener implements Listener {

    private final NametagPlugin plugin;

    public VanishListener(NametagPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Called IMMEDIATELY when a player vanishes using /sv.
     * This event fires BEFORE the player becomes invisible.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerHide(PlayerHideEvent event) {
        Player player = event.getPlayer();
        
        plugin.getLogger().info("PlayerHideEvent fired for " + player.getName());
        
        // Get the display entity
        TextDisplay display = plugin.getNametagManager().getDisplay(player);
        
        if (display != null && display.isValid()) {
            // SOLUTION: Hide the entity from all players instead of removing it
            display.setVisibleByDefault(false);
            
            // Hide from all currently online players
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (!onlinePlayer.equals(player)) {
                    onlinePlayer.hideEntity(plugin, display);
                }
            }
        }
        
        // Mark in manager as vanished (don't remove from tracking)
        plugin.getNametagManager().markAsHidden(player);
    }

    /**
     * Called when a player becomes visible again (unvanishes).
     * Recreate their nametag visibility.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerShow(PlayerShowEvent event) {
        Player player = event.getPlayer();
        
        plugin.getLogger().info("PlayerShowEvent fired for " + player.getName());
        
        // Delay to ensure visibility is fully restored
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !player.isDead() && !VanishAPI.isInvisible(player)) {
                TextDisplay display = plugin.getNametagManager().getDisplay(player);
                
                if (display != null && display.isValid()) {
                    // Make entity visible again
                    display.setVisibleByDefault(true);
                    
                    // Show to all online players
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (!onlinePlayer.equals(player)) {
                            onlinePlayer.showEntity(plugin, display);
                        }
                    }
                } else {
                    // Recreate if it was removed for some reason
                    plugin.getNametagManager().createNametag(player);
                }
                
                plugin.getNametagManager().markAsVisible(player);
            }
        }, 5L);
    }
}