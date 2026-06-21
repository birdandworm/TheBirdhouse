package com.thebirdhouse.plugin;

import lombok.Data;
import java.util.List;

@Data
public class BoardTile {
    private String key;
    private String name;
    private String matchName;
    private List<String> matchItems;
    private String gameType;
    private boolean completed;
    private boolean current;
    private boolean available;
    private boolean free;
    private boolean anyUnique;
    private String special;
    private String region;
    private int quantity;
    private int currentQty;

    // "Collect all" (AND) tiles: require every item in requiredItems.
    private boolean matchAll;
    private List<String> requiredItems;
    private List<String> collectedItems; // lowercased item names already submitted/approved

    // Territory War extras
    private String territoryName;
    private boolean ours;
    private boolean attackable;
    private int defenseLevel;

    // Battleship extras
    private String attackResult; // "hit", "miss", "sunk", or null
}
