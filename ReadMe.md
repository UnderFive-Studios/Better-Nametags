# Nametag Plugin – Full Technical Specification

## Overview

This plugin is a **lightweight custom nametag system** for a Minecraft server running **Paper 1.21.x**. It customizes the player nametag displayed above players' heads while strictly preserving **vanilla visibility behavior** (no showing through walls) and maintaining **full compatibility** with existing plugins such as **LuckPerms, PlaceholderAPI, and SuperVanish**.

The plugin is intentionally simple, clean, and performance-safe. No packet-level manipulation, glow effects, or client-side hacks should be used.

---

## Target Environment

* **Server Software:** Paper
* **Minecraft Version:** 1.21.8
* **Language:** Java
* **API Usage:** Bukkit / Paper API only
* **No NMS or packet libraries** unless absolutely unavoidable (preferred: none)

---

## Core Functional Requirements

### 1. Nametag Content

The nametag consists of **two lines**, displayed **above the player’s head**, ordered top to bottom as follows:

```
          Clan
[Owner] Rank Player [Ping]
```

#### Line 1 – Clan

* Clan name is retrieved via **PlaceholderAPI**
* Placeholder will be provided externally (custom placeholder from a Skript-based clan system)
* Example placeholder usage:

  * `%clan_name%` (exact placeholder name configurable)
* If the player **has no clan**, this entire line will be **hidden** (no empty lines)

#### Line 2 – Main Line

Contains the following elements **in order**:

1. **Rank prefix**

   * Retrieved from **LuckPerms**
   * Uses the player’s primary group prefix
   * Will preserve **colors and formatting** exactly as defined in LuckPerms

2. **Player name**

   * Uses the Minecraft username
   * No modification made unless inherited from rank formatting

3. **Ping display**

   * Format: `[Xms]`
   * Example: `[1ms]`
   * Ping should be retrieved using Paper API
   * Ping should update dynamically at a **reasonable interval** (performance-friendly, e.g. every few seconds)

---

## Visibility & Rendering Rules

### Vanilla Visibility (Critical Requirement)

* Nametags will behave **exactly like vanilla Minecraft**:

  * ❌ NOT visible through walls
  * ❌ No glowing
  * ❌ No packet-based overrides
  * ✅ Line-of-sight required

---

## World & Server Scope

* Plugin will work in **all worlds**
* No world-based exclusions
* No per-world configuration required

---

## Plugin Compatibility

### LuckPerms

* Source of rank data
* Use LuckPerms API to retrieve:

  * Primary group
  * Prefix (preferred) or fallback name if prefix is absent

### PlaceholderAPI

* Used for clan name resolution
* Plugin must:

  * Depend or soft-depend on PlaceholderAPI
  * Gracefully handle missing placeholders

### SuperVanish (Critical Compatibility)

* Plugin **will not interfere** with SuperVanish
* Vanished players:

  * Will not be forcibly shown
  * Will respect vanish visibility rules
  * Will NOT:

  * Force nametags to appear
  * Override entity visibility

---

## Performance Requirements

* Will be safe for medium-sized servers
* Avoids excessive updates or per-tick logic
* Recommended:

  * Scheduled async task for ping updates
  * Minimal scoreboard refreshes

## Config File

```
# NametagPlugin Configuration

# The PlaceholderAPI placeholder to use for clan name
# If this returns empty or the raw placeholder, the clan line is hidden
clan-placeholder: "%clansLite_clanPrefix%"

# Enable or disable ping display in nametags
ping-enabled: true

# How often to update nametag values (ping and clan) in seconds
# Lower values = more responsive but higher server load
# Recommended: 2-3 seconds
update-interval: 3

# Whether to show formatted name in TAB list
# true = Show rank prefix in TAB list
# false = Show vanilla player name in TAB list
tab-list-display: true

# Nametag appearance settings
nametag:
  # Background opacity (0.0 = transparent, 1.0 = fully opaque)
  # Set to 0 for no background box
  background-opacity: 0
  
  # Text shadow for better readability
  text-shadow: true
  
  # Scale of the text (1.0 = normal size)
  scale: 1.0
  
  # Vertical offset above player head (in blocks)
  # Default 0.3 positions it nicely above the head
  vertical-offset: 0.3
  
  # Whether the player name should inherit the rank's color
  # true = Player name will match the rank prefix color
  # false = Player name will always be white
  name-inherits-rank-color: true

  # Whether nametags can be seen through walls (vanilla behavior)
  # true = Visible through walls like vanilla Minecraft (hidden when sneaking)
  # false = Only visible with direct line of sight
  see-through-walls: true

```

---

## Summary

This plugin is intentionally **simple, vanilla-safe, and integration-friendly**. The focus is correctness, compatibility, and clarity rather than advanced visual tricks.
