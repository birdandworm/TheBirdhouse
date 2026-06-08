package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * Payload sent to The Birdhouse backend when submitting a drop proof.
 */
@Data
public class ProofPayload {
    private String roomCode;
    private String playerName;
    private String tileKey;
    private String tileName;
    private String gameType;
    private String itemName;
    private String npcName;
    private int quantity;
    private long timestamp;
}
