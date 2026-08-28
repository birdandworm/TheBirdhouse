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
    // Territory War proofs are reviewed by territory, not by the drop that took it, so the
    // backend stores this alongside the tile name for the approval screen.
    private String territoryName;
    private String gameType;
    private String itemName;
    private String npcName;
    private int quantity;
    // Grand Exchange value of the whole stack. Sent because the backend has no price data
    // of its own, and Clue Trail ranks players partly on the value of what they open.
    private long value;
    private int itemId;
    private long timestamp;
    // True only for proofs the player submitted by hand from the board window. The backend
    // routes these to admin review instead of auto-approving them, because nothing
    // witnessed the drop.
    private boolean manual;
}
