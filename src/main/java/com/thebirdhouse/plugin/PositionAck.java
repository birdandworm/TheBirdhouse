package com.thebirdhouse.plugin;

import lombok.Data;

/**
 * The server's answer to a position report, which is mostly an instruction about what to
 * do next rather than a result.
 *
 * The cadence lives on that side on purpose. Ten seconds is a guess at the right sampling
 * rate and the co-presence threshold it feeds has never been tuned against real players,
 * so both will move — and a number baked into this class would move only as fast as the
 * Plugin Hub reviews a release. Everything here is therefore read from the response and
 * only defaulted when the field is missing.
 */
@Data
public class PositionAck {

    private boolean ok;

    /** The named place the server decided we are standing in, or null for off the board. */
    private String zone;

    /** Seconds to wait before considering another send. */
    private Integer tick;

    /** Seconds after which to send again even if we have not moved. */
    private Integer keepalive;

    /**
     * The room does not want position: the round is over, the game is not an impostor
     * game, or real tasks are switched off. Stop until something changes.
     */
    private boolean stop;

    /**
     * The sample arrived too soon and was discarded. Not a refusal — the room still wants
     * position, so this must not be treated as {@link #stop}.
     */
    private boolean throttled;

    /**
     * You are out of the game.
     *
     * This ack is the only channel the mode has into the client that runs faster than a
     * minute, and dying is the news that cannot wait one: until the player is told, they are
     * visibly still fighting something, which tells the room more than the meeting was
     * supposed to.
     *
     * Never says who. That is not squeamishness — a victim who knew would give it away in the
     * meeting whether they meant to or not, and they are the one person in the room who
     * cannot be asked to argue their way out of it.
     *
     * Arrives with {@link #stop}, since a dead player's position is nobody's evidence.
     */
    private boolean dead;
}
