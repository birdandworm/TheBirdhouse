package com.thebirdhouse.plugin;

import lombok.Data;
import java.util.List;

/**
 * Board data fetched from The Birdhouse API.
 * Contains tile info for drop matching and panel display.
 */
@Data
public class BoardData {
    private String roomCode;
    private String gameType;
    private List<BoardTile> tiles;
    private int rows;
    private int cols;
    private Integer position; // Player's current position (tile race)
}
