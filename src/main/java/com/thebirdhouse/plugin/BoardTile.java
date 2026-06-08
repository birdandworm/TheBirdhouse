package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * A single tile from the board, used for drop matching.
 */
@Data
public class BoardTile {
    private String key;       // Tile identifier (e.g. "2-3" for bingo, "5" for tile race)
    private String name;      // Display name
    private String matchName; // Name to match against drops (OSRS wiki name)
    private String gameType;  // bingo, tilerace, territory_attack, etc.
    private boolean completed;
    private boolean anyUnique; // If true, any unique drop from the matched NPC counts
    private int quantity;     // How many drops needed (default 1)
}
