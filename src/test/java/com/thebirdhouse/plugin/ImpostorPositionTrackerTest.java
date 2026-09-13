package com.thebirdhouse.plugin;

import com.thebirdhouse.plugin.ImpostorPositionTracker.Sample;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * When a position is worth sending, and when the plugin should not be sending at all.
 *
 * Both halves are here because both have a failure mode that is invisible from in game.
 * Sending too little makes an honest player look absent, which in this mode means their
 * alibi quietly stops existing. Sending at all outside a round means collecting a record
 * of where somebody was during a game that is not running, which is the one thing the
 * feature promises not to do.
 */
public class ImpostorPositionTrackerTest {

    private static final long TICK = 10_000;
    private static final long KEEPALIVE = 60_000;

    private static Sample at(int x, int y) {
        return new Sample(x, y, 0, false);
    }

    private static BoardData board(String gameType, String phase) {
        BoardData b = new BoardData();
        b.setGameType(gameType);
        b.setPhase(phase);
        return b;
    }

    // ── Whether to report at all ─────────────────────────────────────────────────

    @Test
    public void anImpostorRoundIsReported() {
        assertTrue(ImpostorPositionTracker.roundIsRunning(board("impostor", "round")));
    }

    @Test
    public void everyOtherPhaseIsNot() {
        // The lobby, the meeting, the vote and the reveal are all for talking. Measuring
        // people through them would collect positions the game has no use for.
        for (String phase : new String[]{"lobby", "meeting", "reveal", "done"}) {
            assertFalse(phase, ImpostorPositionTracker.roundIsRunning(board("impostor", phase)));
        }
    }

    @Test
    public void anotherGameTypeIsNeverReported() {
        // A bingo player has this plugin too, and nothing about a bingo board should ever
        // start a position loop.
        assertFalse(ImpostorPositionTracker.roundIsRunning(board("bingo", "round")));
        assertFalse(ImpostorPositionTracker.roundIsRunning(board("tilerace", null)));
    }

    @Test
    public void aServerThatSendsNoPhaseIsTreatedAsNoRound() {
        // Older backends omit the field. Defaulting the other way would have the plugin
        // reporting into a server that was never expecting it.
        assertFalse(ImpostorPositionTracker.roundIsRunning(board("impostor", null)));
        assertFalse(ImpostorPositionTracker.roundIsRunning(null));
    }

    // ── When to send ─────────────────────────────────────────────────────────────

    @Test
    public void nothingIsSentBeforeTheTickHasElapsed() {
        // Even having moved. The server dictates the rate, and beating it just costs
        // requests that get throttled at the far end.
        assertFalse(ImpostorPositionTracker.dueToSend(at(3200, 9860), at(3201, 9861), 4_000, TICK, KEEPALIVE));
    }

    @Test
    public void theFirstSampleOfARoundAlwaysGoes() {
        // The server has wiped its record, so a player who has not moved since the last
        // round still has to establish where they are starting from.
        assertTrue(ImpostorPositionTracker.dueToSend(null, at(3200, 9860), TICK, TICK, KEEPALIVE));
    }

    @Test
    public void movingSendsOnTheNextTick() {
        assertTrue(ImpostorPositionTracker.dueToSend(at(3200, 9860), at(3204, 9862), TICK, TICK, KEEPALIVE));
    }

    @Test
    public void standingStillSaysNothingUntilTheKeepalive() {
        // The economy of the whole feature: a stationary player costs one request a minute
        // rather than six.
        Sample still = at(3200, 9860);
        assertFalse(ImpostorPositionTracker.dueToSend(still, at(3200, 9860), TICK, TICK, KEEPALIVE));
        assertFalse(ImpostorPositionTracker.dueToSend(still, at(3200, 9860), 45_000, TICK, KEEPALIVE));
    }

    @Test
    public void standingStillStillChecksInEventually() {
        // The reason send-on-change alone is not enough. Silence has to mean "gone", so
        // somebody who is present and motionless must keep saying so — otherwise the server
        // cannot tell them from a closed client, and their time in the zone is either
        // credited to a player who left or stolen from one who stayed.
        Sample still = at(3200, 9860);
        assertTrue(ImpostorPositionTracker.dueToSend(still, at(3200, 9860), KEEPALIVE, TICK, KEEPALIVE));
        assertTrue(ImpostorPositionTracker.dueToSend(still, at(3200, 9860), KEEPALIVE + 5_000, TICK, KEEPALIVE));
    }

    @Test
    public void nothingIsSentWithoutASample() {
        // Logged out, or the player has not loaded yet.
        assertFalse(ImpostorPositionTracker.dueToSend(at(3200, 9860), null, KEEPALIVE, TICK, KEEPALIVE));
        assertFalse(ImpostorPositionTracker.dueToSend(null, null, KEEPALIVE, TICK, KEEPALIVE));
    }

    @Test
    public void aServerRetunedCadenceIsHonoured() {
        // The rate is the server's to change, so the same movement is due or not depending
        // only on what it last said. A twenty-second tick means a ten-second-old move waits.
        assertFalse(ImpostorPositionTracker.dueToSend(at(3200, 9860), at(3204, 9862), 10_000, 20_000, KEEPALIVE));
        assertTrue(ImpostorPositionTracker.dueToSend(at(3200, 9860), at(3204, 9862), 20_000, 20_000, KEEPALIVE));
    }

    // ── What counts as having moved ──────────────────────────────────────────────

    @Test
    public void aChangeOfFloorIsMovement() {
        // Draynor Manor is three floors and all of them are the manor, so this does not
        // change the zone — but it is still movement, and the server times zones by sample,
        // so a silent floor change would stretch the previous reading over it.
        assertNotEquals(new Sample(3108, 3355, 0, false), new Sample(3108, 3355, 2, false));
    }

    @Test
    public void steppingIntoAnInstanceIsMovement() {
        // The most important one to report promptly: inside an instance the player is off
        // the board entirely, witnessing nothing and witnessed by nobody. Coordinates are
        // reused by instances, so without the flag they would look co-located with anyone
        // standing at the same spot in the real world.
        assertNotEquals(new Sample(3200, 9860, 0, false), new Sample(3200, 9860, 0, true));
        assertTrue(ImpostorPositionTracker.dueToSend(
            new Sample(3200, 9860, 0, false), new Sample(3200, 9860, 0, true), TICK, TICK, KEEPALIVE));
    }

    @Test
    public void theSamePlaceIsTheSameSample() {
        assertEquals(at(3200, 9860), at(3200, 9860));
        assertEquals(at(3200, 9860).hashCode(), at(3200, 9860).hashCode());
    }

    // ── Expiring a refusal ───────────────────────────────────────────────────────

    @Test
    public void aStopAppliesToOneRoomAndPhase() {
        // Keyed rather than a bare boolean so a refusal expires by itself. The server says
        // stop during a round, the phase moves to the meeting and back into the next round,
        // and the key no longer matches — so one stale board arriving mid-transition cannot
        // silence the plugin for the rest of the evening.
        String stopped = ImpostorPositionTracker.stateKey("AB12CD", "round");

        assertEquals(stopped, ImpostorPositionTracker.stateKey("AB12CD", "round"));
        assertNotEquals(stopped, ImpostorPositionTracker.stateKey("AB12CD", "meeting"));
        assertNotEquals(stopped, ImpostorPositionTracker.stateKey("ZZ99ZZ", "round"));
    }

    @Test
    public void aRoomWithNoPhaseStillHasADistinctKey() {
        // The key is computed on every poll, including when there is no board at all, so it
        // has to survive nulls rather than throwing inside the scheduler.
        assertNotEquals(
            ImpostorPositionTracker.stateKey(null, null),
            ImpostorPositionTracker.stateKey("AB12CD", "round"));
    }

    // ── The payload ──────────────────────────────────────────────────────────────

    @Test
    public void thePayloadCarriesTheCoordinatesAndNothingInterpreted() {
        // The plugin must not be able to name a place. If a zone could be supplied from
        // here, every bounding box on the server would be decorative.
        PositionPayload p = new Sample(3113, 9843, 0, false).payload("AB12CD");

        assertEquals("AB12CD", p.getRoomCode());
        assertEquals(3113, p.getX());
        assertEquals(9843, p.getY());
        assertEquals(0, p.getPlane());
        assertFalse(p.isInstance());
    }

    @Test
    public void anInstancedSampleKeepsItsRawCoordinates() {
        // Deliberately unresolved: the server discards the position when the flag is set, so
        // resolving it would be work in aid of a value nobody reads.
        PositionPayload p = new Sample(2000, 5000, 1, true).payload("AB12CD");

        assertTrue(p.isInstance());
        assertEquals(2000, p.getX());
        assertEquals(5000, p.getY());
        assertEquals(1, p.getPlane());
    }
}
