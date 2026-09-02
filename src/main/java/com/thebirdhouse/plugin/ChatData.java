package com.thebirdhouse.plugin;

import lombok.Data;

import java.util.List;

/**
 * The caller's own team thread.
 *
 * The backend decides which team that is from the room roster, so there is no team
 * to ask for and none to send. A null teamId means this room has no team chat at
 * all (a solo game, or the player has not joined a team yet) rather than an error.
 */
@Data
public class ChatData {
    private String roomCode;
    private String teamId;
    private String teamName;
    private List<ChatMessage> messages;
}
