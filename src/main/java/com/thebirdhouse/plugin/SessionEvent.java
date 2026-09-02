package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * Session lifecycle event sent to The Birdhouse backend.
 */
@Data
public class SessionEvent {
    private String type; // "login", "logout", "heartbeat"
    private String playerName;
    private long timestamp;
    private long durationMs; // Only set on logout

    // Team status rides along on the session write that already happens rather than
    // getting a reporting loop of its own. shareStatus is sent every time, including
    // when false, because that is what tells the server to clear a world it already
    // stored — switching the toggle off has to take effect, not just stop updating.
    private boolean shareStatus;
    private int world;

    public SessionEvent(String type, String playerName, long timestamp) {
        this.type = type;
        this.playerName = playerName;
        this.timestamp = timestamp;
    }
}
