package com.nametag;

import de.myzelyam.api.vanish.VanishAPI;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Comprehensive event listener for player events.
 * Handles nametag creation, cleanup, and visibility management.
 * 
 * Vanish detection uses SuperVanish API when available.
 */
public class PlayerListener implements Listener {

    private final NametagPlugin plugin;
    
    // Track players who are currently dead (between death and respawn)
    private final Set<UUID> deadPlayers = new HashSet<>();
    
    // Flag for SuperVanish presence
    private final boolean hasSuperVanish;

    public PlayerListener(NametagPlugin plugin) {
        this.plugin = plugin;
        
        // Check if SuperVanish/PremiumVanish is available
        this.hasSuperVanish = Bukkit.getPluginManager().isPluginEnabled("SuperVanish") ||
                              Bukkit.getPluginManager().isPluginEnabled("PremiumVanish");
        
        if (hasSuperVanish) {
            plugin.getLogger().info("Using SuperVanish API for vanish detection");
        }
        
        // Start the unified status check task
        startStatusCheckTask();
    }
    
    /**
     * Unified task that checks player status and manages nametags.
     * Runs every 10 ticks (0.5 seconds).
     */
    private void startStatusCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    boolean isVanished = isPlayerVanished(player);
                    boolean isDead = player.isDead() || deadPlayers.contains(uuid);
                    boolean hasNametag = plugin.getNametagManager().hasNametag(player);
                    
                    // CASE 1: Player is vanished - ensure nametag is hidden (not removed)
                    if (isVanished) {
                        TextDisplay display = plugin.getNametagManager().getDisplay(player);
                        if (display != null && display.isValid()) {
                            // Ensure it's hidden
                            if (display.isVisibleByDefault()) {
                                display.setVisibleByDefault(false);
                                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                                    if (!onlinePlayer.equals(player)) {
                                        onlinePlayer.hideEntity(plugin, display);
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    
                    // CASE 2: Player is dead - remove nametag
                    if (isDead) {
                        if (hasNametag) {
                            plugin.getNametagManager().forceRemoveNametag(player);
                        }
                        continue;
                    }
                    
                    // CASE 3: Player is alive, not vanished
                    if (!hasNametag && player.isOnline()) {
                        // Create nametag
                        plugin.getNametagManager().createNametag(player);
                    } else if (hasNametag) {
                        // Ensure nametag is visible
                        TextDisplay display = plugin.getNametagManager().getDisplay(player);
                        if (display != null && display.isValid() && !display.isVisibleByDefault()) {
                            display.setVisibleByDefault(true);
                            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                                if (!onlinePlayer.equals(player)) {
                                    onlinePlayer.showEntity(plugin, display);
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    // ==================== EVENT HANDLERS ====================

    /**
     * Handle player join - create nametag after delay.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Clear dead status on join
        deadPlayers.remove(uuid);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isPlayerVanished(player) && !player.isDead()) {
                plugin.getNametagManager().createNametag(player);
            }
        }, 10L);
    }

    /**
     * Handle player quit - remove all entities.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Clear tracking
        deadPlayers.remove(uuid);
        
        // Immediate removal
        plugin.getNametagManager().forceRemoveNametag(player);
        
        // Full world scan for this player's entities
        removeAllEntitiesForPlayer(uuid);
        
        // Delayed cleanup for safety
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            removeAllEntitiesForPlayer(uuid);
        }, 5L);
    }

    /**
     * Handle player death - MARK as dead and remove nametag.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        
        // CRITICAL: Mark player as dead to prevent recreation
        deadPlayers.add(uuid);
        
        // Remove nametag
        plugin.getNametagManager().forceRemoveNametag(player);
        
        // Full world scan
        removeAllEntitiesForPlayer(uuid);
    }

    /**
     * Handle player respawn - recreate nametag.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Clear dead status
        deadPlayers.remove(uuid);
        
        // Create nametag after delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isPlayerVanished(player) && !player.isDead()) {
                plugin.getNametagManager().createNametag(player);
            }
        }, 10L);
    }

    /**
     * Handle entity dismount - immediately remove ejected TextDisplays.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDismount(EntityDismountEvent event) {
        Entity entity = event.getEntity();
        
        if (entity instanceof TextDisplay) {
            TextDisplay display = (TextDisplay) entity;
            if (plugin.getNametagManager().isOurDisplay(display)) {
                entity.remove();
            }
        }
    }

    /**
     * Handle world change - recreate nametag in new world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        World fromWorld = event.getFrom();
        
        // Clean up in old world
        removeAllEntitiesForPlayerInWorld(uuid, fromWorld);
        
        // Recreate in new world
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isPlayerVanished(player) && !player.isDead()) {
                plugin.getNametagManager().createNametag(player);
            }
        }, 5L);
    }

    /**
     * Handle teleport - ensure display stays mounted.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isPlayerVanished(player) && !player.isDead()) {
                plugin.getNametagManager().remountDisplay(player);
            }
        }, 2L);
    }

    /**
     * Handle sneak toggle - adjust nametag visibility when see-through-walls is enabled.
     * 
     * Vanilla Minecraft sneaking behavior:
     * - Crouching behind a block: nametag is completely invisible (blocks occlude it)
     * - Crouching in direct line of sight: nametag opacity is reduced (semi-transparent)
     * - Standing: nametag is fully visible, even through walls
     * 
     * We replicate this by toggling setSeeThrough and setTextOpacity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        // Only relevant when see-through-walls is enabled
        if (!plugin.isSeeThroughWalls()) {
            return;
        }

        Player player = event.getPlayer();
        TextDisplay display = plugin.getNametagManager().getDisplay(player);

        if (display == null || !display.isValid()) {
            return;
        }

        if (event.isSneaking()) {
            // Player started sneaking:
            // 1. Disable see-through so blocks occlude the nametag (invisible behind walls)
            // 2. Reduce text opacity for semi-transparent look in direct line of sight
            display.setSeeThrough(false);
            display.setTextOpacity((byte) 64); // ~25% opacity
        } else {
            // Player stopped sneaking - restore full visibility
            if (!isPlayerVanished(player)) {
                display.setSeeThrough(true);
                display.setTextOpacity((byte) -1); // Fully opaque (255 unsigned)
            }
        }
    }

    // ==================== UTILITY METHODS ====================
    
    /**
     * Remove ALL nametag entities for a player from ALL WORLDS.
     */
    private void removeAllEntitiesForPlayer(UUID playerUuid) {
        for (World world : Bukkit.getWorlds()) {
            removeAllEntitiesForPlayerInWorld(playerUuid, world);
        }
    }
    
    /**
     * Remove all nametag entities for a player in a specific world.
     */
    private void removeAllEntitiesForPlayerInWorld(UUID playerUuid, World world) {
        if (world == null) return;
        
        try {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay) {
                    TextDisplay display = (TextDisplay) entity;
                    if (plugin.getNametagManager().isOurDisplay(display)) {
                        UUID ownerUuid = plugin.getNametagManager().getEntityOwner(display);
                        if (ownerUuid != null && ownerUuid.equals(playerUuid)) {
                            entity.remove();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    
    /**
     * Check if a player is vanished.
     * Uses SuperVanish API if available, falls back to metadata check.
     */
    private boolean isPlayerVanished(Player player) {
        // Method 1: SuperVanish/PremiumVanish API (most reliable)
        if (hasSuperVanish) {
            try {
                return VanishAPI.isInvisible(player);
            } catch (Exception e) {
                // API call failed, fall through to metadata check
            }
        }
        
        // Method 2: Metadata check (fallback for other vanish plugins)
        if (player.hasMetadata("vanished")) {
            List<MetadataValue> values = player.getMetadata("vanished");
            for (MetadataValue value : values) {
                if (value.asBoolean()) {
                    return true;
                }
            }
        }
        
        return false;
    }
}