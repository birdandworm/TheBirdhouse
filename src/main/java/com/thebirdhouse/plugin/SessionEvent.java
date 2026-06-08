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

    public SessionEvent(String type, String playerName, long timestamp) {
        this.type = type;
        this.playerName = playerName;
        this.timestamp = timestamp;
    }
}
