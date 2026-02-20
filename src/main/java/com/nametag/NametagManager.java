package com.nametag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages player nametags using TextDisplay entities.
 * 
 * Creates a TextDisplay entity that follows each player, displaying:
 * Line 1: Clan name (if available)
 * Line 2: [Rank] PlayerName [Ping]
 * 
 * Uses PersistentDataContainer to tag our entities for reliable cleanup.
 */
public class NametagManager {

    private final NametagPlugin plugin;
    
    // PDC keys for identification
    private final NamespacedKey NAMETAG_KEY;
    private final NamespacedKey OWNER_KEY;
    
    // Track TextDisplay entities by player UUID
    private final Map<UUID, TextDisplay> nametagDisplays = new HashMap<>();
    
    // Cache previous nametag content to avoid unnecessary updates
    private final Map<UUID, String> contentCache = new HashMap<>();
    
    // Track which players have hidden nametags (vanished)
    private final Set<UUID> hiddenPlayers = new HashSet<>();
    
    // Serializers for converting color codes
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacySection();
    
    // Pattern to extract the last color code from a string
    private static final Pattern LAST_COLOR_PATTERN = Pattern.compile(".*([§&][0-9a-fk-or])");

    public NametagManager(NametagPlugin plugin) {
        this.plugin = plugin;
        this.NAMETAG_KEY = new NamespacedKey(plugin, "nametag");
        this.OWNER_KEY = new NamespacedKey(plugin, "owner");
    }

    /**
     * Create the nametag TextDisplay for a player.
     * 
     * @param player The player to create nametag for
     */
    public void createNametag(Player player) {
        // Check vanish first - don't create if vanished
        if (isPlayerVanished(player)) {
            return;
        }
        
        // Remove existing nametag first if any
        forceRemoveNametag(player);
        
        // Spawn the TextDisplay entity at player's location
        TextDisplay display = player.getWorld().spawn(
            player.getLocation().add(0, getDisplayHeight(player), 0),
            TextDisplay.class,
            textDisplay -> {
                configureDisplay(textDisplay, player);
                // Tag with PDC for identification
                textDisplay.getPersistentDataContainer().set(NAMETAG_KEY, PersistentDataType.BYTE, (byte) 1);
                textDisplay.getPersistentDataContainer().set(OWNER_KEY, PersistentDataType.STRING, player.getUniqueId().toString());
                
                // Set visibility - visible by default
                textDisplay.setVisibleByDefault(true);
            }
        );
        
        // Add display as passenger of player for smooth tracking
        player.addPassenger(display);
        
        // Store reference
        nametagDisplays.put(player.getUniqueId(), display);
        
        // Initial content update
        updateNametagContent(player, display);
        
        // Update TAB list if enabled
        if (plugin.isTabListDisplay()) {
            updateTabList(player);
        }
    }

    /**
     * Configure the TextDisplay entity properties.
     */
    private void configureDisplay(TextDisplay display, Player player) {
        // Billboard - always face the viewer
        display.setBillboard(Display.Billboard.CENTER);
        
        // See-through-walls based on config (true = vanilla-like, visible through walls)
        display.setSeeThrough(plugin.isSeeThroughWalls());
        
        // View range (1.0 = 64 blocks, normal)
        display.setViewRange(1.0f);
        
        // Text shadow for readability
        display.setShadowed(plugin.hasTextShadow());
        
        // Background
        if (plugin.getBackgroundOpacity() <= 0) {
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        } else {
            display.setDefaultBackground(false);
            int alpha = (int) (plugin.getBackgroundOpacity() * 255);
            display.setBackgroundColor(Color.fromARGB(alpha, 0, 0, 0));
        }
        
        // Scale and position transformation
        float scale = plugin.getScale();
        float yOffset = plugin.getVerticalOffset();
        
        display.setTransformation(new Transformation(
            new Vector3f(0, yOffset, 0),  // Translation (height offset)
            new AxisAngle4f(),             // Left rotation
            new Vector3f(scale, scale, scale), // Scale
            new AxisAngle4f()              // Right rotation
        ));
        
        // Text alignment
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        
        // Line width (wider to prevent awkward wrapping)
        display.setLineWidth(200);
        
        // Make it non-persistent (doesn't save to disk)
        display.setPersistent(false);
    }

    /**
     * Update the nametag content for a player.
     * 
     * @param player The player to update
     */
    public void updateNametag(Player player) {
        // VANISH CHECK: If vanished, ensure nametag is hidden
        if (isPlayerVanished(player)) {
            TextDisplay display = nametagDisplays.get(player.getUniqueId());
            if (display != null && display.isValid()) {
                // Just ensure it's hidden, don't remove
                if (display.isVisibleByDefault()) {
                    display.setVisibleByDefault(false);
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (!onlinePlayer.equals(player)) {
                            onlinePlayer.hideEntity(plugin, display);
                        }
                    }
                }
            }
            return;
        }
        
        // DEAD CHECK: Don't update nametag if player is dead
        if (player.isDead()) {
            if (hasNametag(player)) {
                forceRemoveNametag(player);
            }
            return;
        }

        TextDisplay display = nametagDisplays.get(player.getUniqueId());
        if (display == null || !display.isValid()) {
            // Display was removed or invalid, recreate it
            createNametag(player);
            return;
        }
        
        // Update the content
        updateNametagContent(player, display);
    }
    
    /**
     * Update the actual content of a nametag display.
     */
    private void updateNametagContent(Player player, TextDisplay display) {
        // Build the nametag content
        Component content = buildNametagContent(player);
        String contentString = content.toString();
        
        // Check cache to avoid unnecessary updates
        String cached = contentCache.get(player.getUniqueId());
        if (cached != null && cached.equals(contentString)) {
            return; // No change
        }
        
        // Update the display
        display.text(content);
        contentCache.put(player.getUniqueId(), contentString);
    }

    /**
     * Remove the nametag for a player (normal cleanup).
     * 
     * @param player The player whose nametag to remove
     */
    public void removeNametag(Player player) {
        forceRemoveNametag(player);
    }
    
    /**
     * Force remove nametag - aggressive cleanup for death/quit.
     * Removes the entity unconditionally and also removes any TextDisplay passengers.
     * 
     * @param player The player to cleanup
     */
    public void forceRemoveNametag(Player player) {
        UUID uuid = player.getUniqueId();
        TextDisplay display = nametagDisplays.remove(uuid);
        contentCache.remove(uuid);
        hiddenPlayers.remove(uuid);
        
        // Remove tracked display if exists
        if (display != null) {
            try {
                // Eject before removing to prevent task failures
                player.removePassenger(display);
                display.remove();
            } catch (Exception ignored) {}
        }
        
        // Also scan and remove any TextDisplay passengers from the player
        try {
            for (Entity passenger : player.getPassengers()) {
                if (passenger instanceof TextDisplay) {
                    TextDisplay td = (TextDisplay) passenger;
                    if (isOurEntity(td)) {
                        player.removePassenger(td);
                        td.remove();
                    }
                }
            }
        } catch (Exception ignored) {
            // Player might be in invalid state
        }
    }
    
    /**
     * Check if a TextDisplay entity is one of ours using PDC.
     */
    public boolean isOurEntity(TextDisplay display) {
        if (display == null) return false;
        return display.getPersistentDataContainer().has(NAMETAG_KEY, PersistentDataType.BYTE);
    }
    
    /**
     * Get the owner UUID of a nametag entity.
     */
    public UUID getEntityOwner(TextDisplay display) {
        if (display == null) return null;
        String uuidStr = display.getPersistentDataContainer().get(OWNER_KEY, PersistentDataType.STRING);
        if (uuidStr == null) return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Cleanup orphaned nametag entities in all worlds.
     * Called periodically to catch any entities that weren't properly cleaned up.
     */
    public void cleanupOrphanedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay) {
                    TextDisplay display = (TextDisplay) entity;
                    
                    // Check if this is one of our entities
                    if (isOurEntity(display)) {
                        UUID ownerUuid = getEntityOwner(display);
                        
                        // Remove if: no owner, owner offline, or not mounted on owner
                        if (ownerUuid == null) {
                            display.remove();
                            continue;
                        }
                        
                        Player owner = Bukkit.getPlayer(ownerUuid);
                        if (owner == null || !owner.isOnline()) {
                            // Owner is offline - this is an orphan
                            display.remove();
                            continue;
                        }
                        
                        // Check if entity is actually a passenger of the owner
                        if (!owner.getPassengers().contains(display)) {
                            // This entity is orphaned (not mounted)
                            display.remove();
                            nametagDisplays.remove(ownerUuid);
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if a player is vanished using metadata.
     */
    private boolean isPlayerVanished(Player player) {
        return player.hasMetadata("vanished") && 
               player.getMetadata("vanished").stream().anyMatch(meta -> meta.asBoolean());
    }

    /**
     * Update the TAB list display for a player.
     * 
     * @param player The player to update
     */
    public void updateTabList(Player player) {
        if (!plugin.isTabListDisplay()) {
            return;
        }
        
        TextComponent.Builder builder = Component.text();
        
        // 1. Clan
        String clan = getClanName(player);
        if (clan != null && !clan.isEmpty()) {
            builder.append(parseColorCodes(clan));
            builder.append(Component.text(" ")); // Space after clan
        }
        
        // 2. Rank
        String rankPrefix = getRankPrefix(player);
        if (rankPrefix != null && !rankPrefix.isEmpty()) {
            Component prefix = parseColorCodes(rankPrefix);
            builder.append(prefix);
            // Add space if prefix doesn't have one
            if (!rankPrefix.endsWith(" ")) {
                builder.append(Component.text(" "));
            }
        }
        
        // 3. Name (with rank color if enabled)
        builder.append(buildPlayerNameComponent(player, rankPrefix));
        
        player.playerListName(builder.build());
    }

    /**
     * Build the complete nametag content component.
     * 
     * Line 1: Clan (if available)
     * Line 2: [Rank] PlayerName [Ping]
     */
    private Component buildNametagContent(Player player) {
        TextComponent.Builder builder = Component.text();
        boolean hasLine1 = false;
        
        // Line 1: Clan name
        String clan = getClanName(player);
        if (clan != null && !clan.isEmpty()) {
            Component clanComponent = parseColorCodes(clan);
            builder.append(clanComponent);
            hasLine1 = true;
        }
        
        // Add newline if we have a clan
        if (hasLine1) {
            builder.append(Component.newline());
        }
        
        // Line 2: [Rank] PlayerName [Ping]
        // Rank prefix
        String rankPrefix = getRankPrefix(player);
        if (rankPrefix != null && !rankPrefix.isEmpty()) {
            Component prefixComponent = parseColorCodes(rankPrefix);
            builder.append(prefixComponent);
            
            // Add space after prefix if it doesn't end with one
            if (!rankPrefix.endsWith(" ")) {
                builder.append(Component.text(" "));
            }
        }
        
        // Player name - optionally inherit rank color
        builder.append(buildPlayerNameComponent(player, rankPrefix));
        
        // Ping display
        if (plugin.isPingEnabled()) {
            int ping = player.getPing();
            NamedTextColor pingColor = getPingColor(ping);
            
            builder.append(Component.text(" "))
                   .append(Component.text("[", NamedTextColor.GRAY))
                   .append(Component.text(ping + "ms", pingColor))
                   .append(Component.text("]", NamedTextColor.GRAY));
        }
        
        return builder.build();
    }
    
    /**
     * Build the player name component with optional rank color inheritance.
     */
    private Component buildPlayerNameComponent(Player player, String rankPrefix) {
        String playerName = player.getName();
        
        if (plugin.isNameInheritsRankColor() && rankPrefix != null && !rankPrefix.isEmpty()) {
            // Extract the last color code from the rank prefix
            TextColor rankColor = extractLastColor(rankPrefix);
            if (rankColor != null) {
                return Component.text(playerName, rankColor);
            }
        }
        
        // Default: white
        return Component.text(playerName, NamedTextColor.WHITE);
    }
    
    /**
     * Extract the last color from a legacy color-coded string.
     */
    private TextColor extractLastColor(String text) {
        if (text == null || text.isEmpty()) return null;
        
        // Normalize color codes
        text = text.replace('&', '§');
        
        // Find the last color code
        Matcher matcher = LAST_COLOR_PATTERN.matcher(text);
        if (matcher.find()) {
            String colorCode = matcher.group(1);
            char code = colorCode.charAt(1);
            
            // Map color code to TextColor
            return switch (Character.toLowerCase(code)) {
                case '0' -> NamedTextColor.BLACK;
                case '1' -> NamedTextColor.DARK_BLUE;
                case '2' -> NamedTextColor.DARK_GREEN;
                case '3' -> NamedTextColor.DARK_AQUA;
                case '4' -> NamedTextColor.DARK_RED;
                case '5' -> NamedTextColor.DARK_PURPLE;
                case '6' -> NamedTextColor.GOLD;
                case '7' -> NamedTextColor.GRAY;
                case '8' -> NamedTextColor.DARK_GRAY;
                case '9' -> NamedTextColor.BLUE;
                case 'a' -> NamedTextColor.GREEN;
                case 'b' -> NamedTextColor.AQUA;
                case 'c' -> NamedTextColor.RED;
                case 'd' -> NamedTextColor.LIGHT_PURPLE;
                case 'e' -> NamedTextColor.YELLOW;
                case 'f' -> NamedTextColor.WHITE;
                default -> null;
            };
        }
        
        return null;
    }

    /**
     * Parse legacy color codes (& and §) to Adventure components.
     */
    private Component parseColorCodes(String text) {
        // Convert & to § for consistency
        text = text.replace('&', '§');
        return legacySerializer.deserialize(text);
    }

    /**
     * Get color for ping value.
     */
    private NamedTextColor getPingColor(int ping) {
        if (ping < 50) {
            return NamedTextColor.GREEN;
        } else if (ping < 100) {
            return NamedTextColor.YELLOW;
        } else if (ping < 200) {
            return NamedTextColor.GOLD;
        } else {
            return NamedTextColor.RED;
        }
    }

    /**
     * Calculate display height based on player state.
     */
    private double getDisplayHeight(Player player) {
        // Base height above player's head
        return 2.0 + plugin.getVerticalOffset();
    }

    /**
     * Get clan name from PlaceholderAPI.
     */
    private String getClanName(Player player) {
        if (!plugin.hasPlaceholderApi()) {
            return null;
        }

        try {
            String placeholder = plugin.getClanPlaceholder();
            String result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);

            // Check if resolved (not empty and not the raw placeholder)
            if (result == null || result.isEmpty() || result.equals(placeholder)) {
                return null;
            }

            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get rank prefix from LuckPerms.
     */
    private String getRankPrefix(Player player) {
        if (!plugin.hasLuckPerms() || plugin.getLuckPermsApi() == null) {
            return "";
        }

        try {
            User user = plugin.getLuckPermsApi().getUserManager().getUser(player.getUniqueId());
            
            if (user == null) {
                return "";
            }

            // Get prefix from cached meta data
            String prefix = user.getCachedData().getMetaData().getPrefix();

            if (prefix != null && !prefix.isEmpty()) {
                return prefix;
            }

            // Fallback: use primary group name if not "default"
            String primaryGroup = user.getPrimaryGroup();
            if (primaryGroup != null && !primaryGroup.equalsIgnoreCase("default")) {
                String displayGroup = primaryGroup.substring(0, 1).toUpperCase() 
                    + primaryGroup.substring(1).toLowerCase();
                return "§7[" + displayGroup + "]";
            }

            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Re-mount the display entity after teleport/respawn.
     */
    public void remountDisplay(Player player) {
        // If vanished, don't remount - just ensure it's hidden
        if (isPlayerVanished(player)) {
            TextDisplay display = nametagDisplays.get(player.getUniqueId());
            if (display != null && display.isValid()) {
                if (display.isVisibleByDefault()) {
                    display.setVisibleByDefault(false);
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        if (!onlinePlayer.equals(player)) {
                            onlinePlayer.hideEntity(plugin, display);
                        }
                    }
                }
            }
            return;
        }
        
        TextDisplay display = nametagDisplays.get(player.getUniqueId());
        if (display != null && display.isValid()) {
            // Re-add as passenger
            if (!player.getPassengers().contains(display)) {
                player.addPassenger(display);
            }
        } else {
            // Recreate if missing
            createNametag(player);
        }
    }

    /**
     * Check if a player has a nametag display.
     */
    public boolean hasNametag(Player player) {
        TextDisplay display = nametagDisplays.get(player.getUniqueId());
        return display != null && display.isValid();
    }
    
    /**
     * Check if a display is one of our managed displays (for dismount handler).
     */
    public boolean isOurDisplay(TextDisplay display) {
        return isOurEntity(display);
    }
    
    /**
     * Get the TextDisplay for a player (for external access).
     */
    public TextDisplay getDisplay(Player player) {
        return nametagDisplays.get(player.getUniqueId());
    }
    
    /**
     * Mark a player as having a hidden nametag.
     */
    public void markAsHidden(Player player) {
        hiddenPlayers.add(player.getUniqueId());
    }
    
    /**
     * Mark a player as having a visible nametag.
     */
    public void markAsVisible(Player player) {
        hiddenPlayers.remove(player.getUniqueId());
    }
    
    /**
     * Check if a player's nametag is marked as hidden.
     */
    public boolean isMarkedHidden(Player player) {
        return hiddenPlayers.contains(player.getUniqueId());
    }
}