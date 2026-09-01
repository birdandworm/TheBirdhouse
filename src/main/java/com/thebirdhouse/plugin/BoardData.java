package com.thebirdhouse.plugin;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class BoardData {
    private String roomCode;
    private String gameType;
    private List<BoardTile> tiles;
    private int rows;
    private int cols;
    private Integer position;
    private Long deadline;

    // Whether the game is still live. When false (game ended / deadline passed),
    // the panel backs off its polling cadence. Defaults true for older API responses.
    private Boolean active;
    private String status;

    // Whether the event has started/locked. When explicitly false, drops don't count
    // yet — the plugin warns the player to screenshot instead of auto-submitting.
    private Boolean started;

    // Tile Race: opponent positions
    private List<OpponentPosition> opponents;

    // Territory War: ownership and adjacency info
    private TerritoryMeta territoryMeta;

    // Battleship: attack/defense data
    private BattleshipMeta battleshipMeta;

    // The Delve: spendable supplies, descent progress, and which bosses pay what
    private DelveMeta delveMeta;

    @Data
    public static class OpponentPosition {
        private String name;
        private int position;
    }

    @Data
    public static class TerritoryMeta {
        private Map<String, List<String>> connections;
        private Map<String, TerritoryOwner> owners;
        private Map<String, String> ownerNames;
    }

    @Data
    public static class TerritoryOwner {
        private String owner;
        private boolean ours;
        private int defense;
        private boolean attackable;
    }

    @Data
    public static class BattleshipMeta {
        private Map<String, String> defenseGrid;
        private List<BattleshipShip> ourShips;
        private List<BattleshipShip> sunkEnemyShips;
        // Public enemy fleet summary (name + sunk status, no cell positions).
        private List<BattleshipShip> enemyFleet;
        // Ship counts so the overlay can show how many opponent ships remain afloat.
        private Integer enemyShipsTotal;
        private Integer enemyShipsRemaining;
        private Integer myShipsTotal;
        private Integer myShipsRemaining;
        private String turn;
        private String myTeam;
        private String enemyTeam;
    }

    /**
     * The Delve runs on supplies, which are earned by killing bosses and spent opening
     * rooms. The panel needs the spendable total and the per-boss rates, because "which
     * boss should I kill and what is it worth" is the only in-game decision the mode has.
     */
    @Data
    public static class DelveMeta {
        private int supplies;
        private int fromKills;
        private int bonusEarned;
        private int spent;
        private int sigilsHeld;
        private int sigilsNeeded;
        private int roomsOpened;
        private boolean vaultCleared;
        private List<DelveBoss> bosses;
    }

    @Data
    public static class DelveBoss {
        private String name;
        private String tier;  // easy | mid | hard
        private int rate;     // supplies per kill
        private int kills;    // banked so far for this party
    }

    @Data
    public static class BattleshipShip {
        private String name;
        private int size;
        // Gson delivers JSON arrays-of-arrays as List<List<Number>>, not int[][].
        private List<List<Number>> cells;
        private boolean sunk;
    }
}
