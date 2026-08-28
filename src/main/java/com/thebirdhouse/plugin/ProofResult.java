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

    static ProofResult accepted() {
        return new ProofResult(true, false, null);
    }

    static ProofResult duplicate(String message) {
        return new ProofResult(true, true, message);
    }

    static ProofResult rejected(String message) {
        return new ProofResult(false, false, message);
    }
}
