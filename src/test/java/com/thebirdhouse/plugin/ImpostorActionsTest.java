package com.thebirdhouse.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Impostor verbs must never appear on another game type. A leftover "you are
 * the impostor" flag from an earlier room is not enough.
 */
public class ImpostorActionsTest {

    private static BoardData board(String gameType, String phase) {
        BoardData b = new BoardData();
        b.setGameType(gameType);
        b.setPhase(phase);
        return b;
    }

    @Test
    public void commandsAreOfferedOnlyInALiveImpostorRound() {
        assertTrue(ImpostorActions.commandsAllowed(board("impostor", "round")));
    }

    @Test
    public void anImpostorLobbyOrMeetingDoesNotOfferThem() {
        assertFalse(ImpostorActions.commandsAllowed(board("impostor", "lobby")));
        assertFalse(ImpostorActions.commandsAllowed(board("impostor", "meeting")));
        assertFalse(ImpostorActions.commandsAllowed(board("impostor", "done")));
    }

    @Test
    public void aFreshAckPhaseBeatsAStaleLobbyBoard() {
        assertTrue(ImpostorActions.commandsAllowed(board("impostor", "lobby"), "round"));
        assertFalse(ImpostorActions.commandsAllowed(board("impostor", "round"), "meeting"));
        assertFalse(ImpostorActions.commandsAllowed(board("bingo", "lobby"), "round"));
    }

    @Test
    public void eliminateIsOfferedOncePerPlayerNotOncePerMenuRow() {
        // Follow, Trade and a plugin lookup are three rows for one crewmate.
        assertEquals(
            java.util.Arrays.asList("Toe Gaps"),
            new java.util.ArrayList<>(ImpostorActions.distinctOtherNames(
                java.util.Arrays.asList("Toe Gaps (level-99)", "Toe Gaps", "Toe Gaps (level-99)"),
                "birdandworm")));
    }

    @Test
    public void everyPlayerInAStackCanBeEliminated() {
        assertEquals(
            java.util.Arrays.asList("Toe Gaps", "Lost Motem"),
            new java.util.ArrayList<>(ImpostorActions.distinctOtherNames(
                java.util.Arrays.asList("Toe Gaps (level-99)", "Lost Motem (level-104)"),
                "birdandworm")));
    }

    @Test
    public void theImpostorIsNeverOfferedTheirOwnNameOrAMaskedOne() {
        assertTrue(ImpostorActions.distinctOtherNames(
            java.util.Arrays.asList("BirdAndWorm (level-99)", "???", ""), "birdandworm").isEmpty());
    }

    @Test
    public void blackoutRewritesWalkHereAndPluginLookupsToo() {
        java.util.Set<String> others = java.util.Collections.singleton("gim ancestor");
        assertTrue(ImpostorActions.namesPlayer("GIM Ancestor (level-124)", "GIM Ancestor", others));
        assertTrue(ImpostorActions.namesPlayer("Walk here GIM Ancestor (level-124)", "Walk here GIM Ancestor", others));
        assertFalse(ImpostorActions.namesPlayer("a rock", "a rock", others));
    }

    @Test
    public void eliminateRangeMatchesTheServerEightTiles() {
        assertEquals(8, ImpostorActions.KILL_RANGE_TILES);
        assertTrue(ImpostorActions.withinKillRange(100, 100, 0, 108, 100, 0));
        assertFalse(ImpostorActions.withinKillRange(100, 100, 0, 109, 100, 0));
        assertFalse(ImpostorActions.withinKillRange(100, 100, 0, 100, 100, 1));
    }

    @Test
    public void anotherGameTypeNeverOffersThem() {
        for (String type : new String[]{"bingo", "tilerace", "territory", "chipdrop", "battleship"}) {
            assertFalse(type, ImpostorActions.commandsAllowed(board(type, "round")));
            assertFalse(type, ImpostorActions.commandsAllowed(board(type, null)));
        }
        assertFalse(ImpostorActions.commandsAllowed(null));
    }
}
