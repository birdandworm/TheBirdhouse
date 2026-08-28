package com.thebirdhouse.plugin;

import lombok.Data;
import java.util.List;
import java.util.Map;

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

    // Set by the host in the site's tile editor, and only sent when non-empty: the
    // picture shown on the tile, and a short note ("solo only, no alts") that is a rule
    // rather than a hint, so it belongs anywhere the tile is shown.
    private String image;
    private String description;

    // The Delve: bonus-supply drops are repeatable and are never required, so they must
    // not be rendered as a "remaining" checklist the clan is expected to clear.
    private boolean repeatable;
    private boolean optional;
    private String tier;

    // Bounty Board: what a bounty pays, when its window shuts, and how many teams have
    // already claimed it. The first claim takes full points and later ones a fraction, so
    // claimsSoFar is what decides whether a bounty is still worth chasing.
    private int points;
    private Long closesAt;
    private int claimsSoFar;

    // "Collect all" (AND) tiles: require every slot in matchGroups to be filled.
    private boolean matchAll;
    private List<String> requiredItems;
    private List<String> collectedItems; // lowercased item names already submitted/approved
    // Per-item required quantity and collected count, keyed by lowercased item name.
    // Missing entries mean "1 required" / "0 collected".
    private Map<String, Integer> itemQuantities;
    private Map<String, Integer> collectedCounts;
    // Slots (OR-groups): each slot is satisfied by any one of its items reaching qty.
    private List<MatchGroup> matchGroups;

    @Data
    public static class MatchGroup {
        private List<String> items; // lowercased acceptable item names for this slot
        private int qty;
    }

    // Territory War extras
    private String territoryName;
    private boolean ours;
    private boolean attackable;
    private int defenseLevel;

    // Battleship extras
    private String attackResult; // "hit", "miss", "sunk", or null
}
