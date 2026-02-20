package com.nametag;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * NametagPlugin - Custom two-line nametag display using TextDisplay entities.
 * 
 * Line 1: Clan name (hidden if no clan)
 * Line 2: [Rank] PlayerName [Ping]
 * 
 * Uses TextDisplay entities to preserve vanilla visibility behavior.
 */
public class NametagPlugin extends JavaPlugin {

    private static NametagPlugin instance;
    private NametagManager nametagManager;
    private BukkitTask updateTask;
    private BukkitTask cleanupTask;

    // Configuration values
    private String clanPlaceholder;
    private boolean pingEnabled;
    private int updateInterval;
    private boolean tabListDisplay;
    
    // Nametag appearance
    private float backgroundOpacity;
    private boolean textShadow;
    private float scale;
    private float verticalOffset;
    private boolean nameInheritsRankColor;
    private boolean seeThroughWalls;

    // Integration flags
    private boolean hasLuckPerms;
    private boolean hasPlaceholderApi;
    private LuckPerms luckPermsApi;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config and load values
        saveDefaultConfig();
        loadConfiguration();

        // Check integrations
        checkIntegrations();

        // Initialize the nametag manager
        nametagManager = new NametagManager(this);

        // Register event listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        
        // Register SuperVanish/PremiumVanish event listener if present
        if (Bukkit.getPluginManager().isPluginEnabled("SuperVanish") ||
            Bukkit.getPluginManager().isPluginEnabled("PremiumVanish")) {
            try {
                getServer().getPluginManager().registerEvents(new VanishListener(this), this);
                getLogger().info("SuperVanish/PremiumVanish event listener registered!");
            } catch (Exception e) {
                getLogger().warning("Failed to register vanish listener: " + e.getMessage());
            }
        }

        // Register LuckPerms listener for real-time rank updates
        if (hasLuckPerms) {
            registerLuckPermsListener();
        }

        // Apply nametags to any players already online (handles /reload scenarios)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                nametagManager.createNametag(player);
            }
        }, 5L);

        // Start the update task for ping and clan refresh
        startUpdateTask();
        
        // Start the cleanup task to remove orphaned nametag entities
        startCleanupTask();

        getLogger().info("NametagPlugin has been enabled!");
    }

    @Override
    public void onDisable() {
        // Cancel tasks
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }

        // Clean up all nametags and orphaned entities
        if (nametagManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                nametagManager.forceRemoveNametag(player);
            }
            // Final cleanup of any orphaned entities
            nametagManager.cleanupOrphanedEntities();
        }

        getLogger().info("NametagPlugin has been disabled!");
    }

    /**
     * Load configuration values from config.yml
     */
    private void loadConfiguration() {
        reloadConfig();
        
        clanPlaceholder = getConfig().getString("clan-placeholder", "%clan_name%");
        pingEnabled = getConfig().getBoolean("ping-enabled", true);
        updateInterval = getConfig().getInt("update-interval", 3);
        tabListDisplay = getConfig().getBoolean("tab-list-display", false);
        
        // Nametag appearance
        backgroundOpacity = (float) getConfig().getDouble("nametag.background-opacity", 0.0);
        textShadow = getConfig().getBoolean("nametag.text-shadow", true);
        scale = (float) getConfig().getDouble("nametag.scale", 1.0);
        verticalOffset = (float) getConfig().getDouble("nametag.vertical-offset", 0.3);
        nameInheritsRankColor = getConfig().getBoolean("nametag.name-inherits-rank-color", true);
        seeThroughWalls = getConfig().getBoolean("nametag.see-through-walls", true);

        // Ensure minimum interval
        if (updateInterval < 1) {
            updateInterval = 1;
            getLogger().warning("update-interval was too low, set to minimum of 1 second");
        }
    }

    /**
     * Check which optional integrations are available.
     */
    private void checkIntegrations() {
        // Check LuckPerms
        hasLuckPerms = Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
        if (hasLuckPerms) {
            try {
                luckPermsApi = LuckPermsProvider.get();
                getLogger().info("LuckPerms detected - rank prefixes will be displayed");
            } catch (IllegalStateException e) {
                getLogger().warning("LuckPerms API not available: " + e.getMessage());
                hasLuckPerms = false;
            }
        } else {
            getLogger().warning("LuckPerms not found - rank prefixes will be unavailable");
        }

        // Check PlaceholderAPI
        hasPlaceholderApi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (hasPlaceholderApi) {
            getLogger().info("PlaceholderAPI detected - clan names will be displayed");
        } else {
            getLogger().warning("PlaceholderAPI not found - clan display disabled");
        }

        // Check vanish plugins
        if (Bukkit.getPluginManager().isPluginEnabled("SuperVanish") ||
            Bukkit.getPluginManager().isPluginEnabled("PremiumVanish")) {
            getLogger().info("Vanish plugin detected - compatibility mode active");
        }
    }

    /**
     * Register LuckPerms event listener for real-time rank updates.
     */
    private void registerLuckPermsListener() {
        if (luckPermsApi == null) return;

        EventBus eventBus = luckPermsApi.getEventBus();
        eventBus.subscribe(this, UserDataRecalculateEvent.class, event -> {
            // Find the player with this UUID and update their nametag
            Player player = Bukkit.getPlayer(event.getUser().getUniqueId());
            if (player != null && player.isOnline()) {
                // Run on main thread since we're modifying entities
                Bukkit.getScheduler().runTask(this, () -> {
                    nametagManager.updateNametag(player);
                    
                    // Update TAB list if enabled
                    if (tabListDisplay) {
                        nametagManager.updateTabList(player);
                    }
                });
            }
        });
        
        getLogger().info("LuckPerms event listener registered for real-time updates");
    }

    /**
     * Start the task that updates ping and clan values for all players.
     */
    private void startUpdateTask() {
        long intervalTicks = updateInterval * 20L;

        updateTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                nametagManager.updateNametag(player);
            }
        }, intervalTicks, intervalTicks);
    }
    
    /**
     * Start the cleanup task that removes orphaned nametag entities.
     * Runs every 2 seconds to catch any entities that weren't properly cleaned up.
     */
    private void startCleanupTask() {
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            nametagManager.cleanupOrphanedEntities();
        }, 40L, 40L); // Every 2 seconds (40 ticks)
    }

    // Getters

    public static NametagPlugin getInstance() {
        return instance;
    }

    public NametagManager getNametagManager() {
        return nametagManager;
    }

    public String getClanPlaceholder() {
        return clanPlaceholder;
    }

    public boolean isPingEnabled() {
        return pingEnabled;
    }

    public boolean isTabListDisplay() {
        return tabListDisplay;
    }

    public boolean hasLuckPerms() {
        return hasLuckPerms;
    }

    public boolean hasPlaceholderApi() {
        return hasPlaceholderApi;
    }

    public LuckPerms getLuckPermsApi() {
        return luckPermsApi;
    }

    public float getBackgroundOpacity() {
        return backgroundOpacity;
    }

    public boolean hasTextShadow() {
        return textShadow;
    }

    public float getScale() {
        return scale;
    }

    public float getVerticalOffset() {
        return verticalOffset;
    }
    
    public boolean isNameInheritsRankColor() {
        return nameInheritsRankColor;
    }

    public boolean isSeeThroughWalls() {
        return seeThroughWalls;
    }
}