package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * A corpse the server is willing to tell this client about — one zone, no killer, no uid.
 */
@Data
public class ImpostorBody {
    private int x;
    private int y;
    private int plane;
    private String name;
}
