package com.thebirdhouse.plugin;

import lombok.Data;

import java.util.List;

/**
 * The caller's own team roster with status attached.
 *
 * Like chat, the team comes from the room roster rather than the request, so there is
 * none to ask for. A null teamId means this room has no teams at all.
 *
 * `sharing` is about the caller, not the team: false means the player has status
 * sharing switched off, and the server has therefore returned no members. That check
 * lives on the server so the trade stays honest — see your team only while they can
 * see you.
 */
@Data
public class PresenceData {
    private String roomCode;
    private String teamId;
    private String teamName;
    private boolean sharing;
    private List<PresenceMember> members;
}
