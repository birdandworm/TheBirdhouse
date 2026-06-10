package com.thebirdhouse.plugin;

import lombok.Data;

@Data
public class BoardTile {
    private String key;
    private String name;
    private String matchName;
    private String gameType;
    private boolean completed;
    private boolean current;
    private boolean available;
    private boolean free;
    private boolean anyUnique;
    private String special;
    private String region;
    private int quantity;

    // Territory War extras
    private String territoryName;
    private boolean ours;
    private boolean attackable;
    private int defenseLevel;

    // Battleship extras
    private String attackResult; // "hit", "miss", or null
}
