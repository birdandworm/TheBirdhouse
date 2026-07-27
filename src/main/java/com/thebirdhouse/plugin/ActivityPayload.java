package com.thebirdhouse.plugin;

import lombok.Data;

import java.util.Map;

/**
 * A compact, batched activity delta sent to POST /loot (with batch=true).
 *
 * The plugin accumulates these values locally and flushes one payload on each
 * session heartbeat + logout, so leaderboard stats cost a single request per flush
 * rather than one per kill. The server folds each field into a running per-room,
 * per-player total, so every payload is a DELTA since the last flush.
 */
@Data
public class ActivityPayload {
    /** Marks this as the batched-activity variant of the /loot endpoint. */
    private boolean batch = true;
    /** Explicit event room; may be null, in which case the server attributes the
     *  delta to whatever event rooms the player is currently active in. */
    private String roomCode;
    /** Active (non-AFK) playtime in ms since the last flush. */
    private long activeMs;
    /** Total GE value of all loot since the last flush (no minimum). */
    private long lootGp;
    /** Number of NPC kills (loot events) since the last flush. */
    private int kills;
    /** Value of the single biggest item stack seen this event (running max). */
    private long biggest;
    /** Source NPC of the biggest stack. */
    private String biggestSource;
    /** Per-NPC kill counts since the last flush: { npcName: count }. */
    private Map<String, Integer> npcKills;
}
