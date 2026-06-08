package com.thebirdhouse.plugin;

import lombok.Data;
import lombok.AllArgsConstructor;

/**
 * Represents a successful match between a received drop and a board tile.
 */
@Data
@AllArgsConstructor
public class TileMatch {
    private String tileKey;
    private String tileName;
    private String gameType;
}
