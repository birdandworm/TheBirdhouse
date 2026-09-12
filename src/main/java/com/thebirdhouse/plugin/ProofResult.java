package com.thebirdhouse.plugin;

import lombok.Value;

/**
 * Outcome of a proof submission.
 *
 * The backend answers a rejected proof with HTTP 200 and a {@code reason} in the body
 * (mismatched item, hidden tile, event not started), so a bare success flag on the HTTP
 * status is not enough to tell a player why nothing happened.
 */
@Value
public class ProofResult {

    boolean ok;
    boolean duplicate;
    String message;

    /**
     * Kills banked on this tile so far, or 0 when the submission was not a kill.
     *
     * A kill-count tile keeps one proof per player and counts up, rather than filing a
     * proof per kill, so only the first kill's screenshot is worth anything. This is how
     * the client learns a tally is open and stops capturing for that tile.
     */
    int kills;

    static ProofResult accepted() {
        return new ProofResult(true, false, null, 0);
    }

    static ProofResult accepted(int kills) {
        return new ProofResult(true, false, null, kills);
    }

    static ProofResult duplicate(String message) {
        return new ProofResult(true, true, message, 0);
    }

    static ProofResult rejected(String message) {
        return new ProofResult(false, false, message, 0);
    }
}
