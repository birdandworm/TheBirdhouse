package com.thebirdhouse.plugin;

import org.junit.Test;

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
    public void eliminateIsOfferedOncePerMenuNotOncePerPlayerRow() {
        assertTrue(ImpostorActions.isFirstPlayerOption(net.runelite.api.MenuAction.PLAYER_FIRST_OPTION.getId()));
        assertFalse(ImpostorActions.isFirstPlayerOption(net.runelite.api.MenuAction.PLAYER_SECOND_OPTION.getId()));
        assertFalse(ImpostorActions.isFirstPlayerOption(net.runelite.api.MenuAction.PLAYER_THIRD_OPTION.getId()));
    }

    @Test
    public void blackoutRewritesWalkHereAndPluginLookupsToo() {
        java.util.Set<String> others = java.util.Collections.singleton("gim ancestor");
        assertTrue(ImpostorActions.namesPlayer("GIM Ancestor (level-124)", "GIM Ancestor", others));
        assertTrue(ImpostorActions.namesPlayer("Walk here GIM Ancestor (level-124)", "Walk here GIM Ancestor", others));
        assertFalse(ImpostorActions.namesPlayer("a rock", "a rock", others));
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
