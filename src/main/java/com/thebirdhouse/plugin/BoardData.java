package com.thebirdhouse.plugin;

import lombok.Data;
import java.util.List;

/**
 * Board data fetched from The Birdhouse API.
 * Contains tile info for drop matching.
 */
@Data
public class BoardData {
    private String roomCode;
    private String gameType;
    private List<BoardTile> tiles;
}
