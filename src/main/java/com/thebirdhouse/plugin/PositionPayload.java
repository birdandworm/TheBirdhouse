package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * Where the player is standing, sent during a round of The Impostor.
 *
 * Raw world coordinates and nothing else. The plugin deliberately does not know what a
 * zone is: the server owns the table that turns a coordinate into a named place, because
 * a wrong bounding box is then a deploy on their side rather than a Plugin Hub review and
 * a wait for every clanmate to update. Sending a region id or a place name from here
 * would move that decision into the half that is expensive to change.
 */
@Data
public class PositionPayload {

    private String roomCode;
    private int x;
    private int y;
    private int plane;

    /**
     * Whether the player is inside instanced content.
     *
     * The coordinates are sent exactly as the client reports them even when this is true,
     * which looks careless and is not. Instanced regions reuse coordinates, so the usual
     * move would be to resolve the real position — but the server's rule is that anyone
     * inside an instance is in no zone at all, so it throws the coordinates away and reads
     * only this flag. Resolving them would be work in aid of a value nobody looks at.
     */
    private boolean instance;

    /**
     * "Nothing to report — is there anything I should know?"
     *
     * The mode has to be able to tell a player they are dead, and a kill lands at the instant
     * a round closes, when there is by definition no round to report a position for. Rather
     * than keep sending coordinates outside a round so that the answer has something to ride
     * on, this says plainly that there is no position in this request and the fields below are
     * not to be read.
     *
     * The coordinates are zeroed rather than merely ignored, because "the server promises not
     * to look" is a weaker guarantee than never having sent them — and this feature carries an
     * IP warning and a documented promise that location leaves the client only while a round is
     * running. Keeping that promise exactly is worth one boolean.
     */
    private boolean statusOnly;

    public PositionPayload(String roomCode, int x, int y, int plane, boolean instance) {
        this.roomCode = roomCode;
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.instance = instance;
    }

    /** A request that carries no location at all, only the question. */
    public static PositionPayload statusOnly(String roomCode) {
        PositionPayload p = new PositionPayload(roomCode, 0, 0, 0, false);
        p.statusOnly = true;
        return p;
    }
}
