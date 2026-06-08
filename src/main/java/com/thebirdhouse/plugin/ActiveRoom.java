package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * Represents an active room the player is in.
 */
@Data
public class ActiveRoom {
    private String code;
    private String gameType;
}
