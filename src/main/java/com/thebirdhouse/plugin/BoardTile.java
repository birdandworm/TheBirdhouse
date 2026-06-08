package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * A single tile from the board, used for drop matching and panel display.
 */
@Data
public class BoardTile {
    private String key;       // Tile identifier (e.g. "2-3" for bingo, "5" for tile race)
    private String name;      // Display name
    private String matchName; // Name to match against drops (OSRS wiki name)
    private String gameType;  // bingo, tilerace, territory_attack, etc.
    private boolean completed;
    private boolean current;   // True if this is the player's current tile (tile race)
    private boolean available; // True if tile is claimable (chip drop)
    private boolean free;      // True if this is a free space (bingo)
    private boolean anyUnique; // If true, any unique drop from the matched NPC counts
    private String special;    // Special tile type for tile race (start, finish, forward, backward)
    private String region;     // Territory region name
    private int quantity;      // How many drops needed (default 1)
}
