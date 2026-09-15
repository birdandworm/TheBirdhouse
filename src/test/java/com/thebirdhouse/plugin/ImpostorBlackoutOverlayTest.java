package com.thebirdhouse.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImpostorBlackoutOverlayTest {

    private static BoardData board(String gameType, String phase) {
        BoardData b = new BoardData();
        b.setGameType(gameType);
        b.setPhase(phase);
        return b;
    }

    @Test
    public void livingCrewLoseNamesOnlyDuringALiveBlackout() {
        assertTrue(ImpostorBlackoutOverlay.shouldMask(board("impostor", "round"), true, false, false));
    }

    @Test
    public void theImpostorStillReadsNames() {
        assertFalse(ImpostorBlackoutOverlay.shouldMask(board("impostor", "round"), true, true, false));
    }

    @Test
    public void aBingoBoardNeverMasksNames() {
        assertFalse(ImpostorBlackoutOverlay.shouldMask(board("bingo", "round"), true, false, false));
    }

    @Test
    public void lightsOnLeavesNamesAlone() {
        assertFalse(ImpostorBlackoutOverlay.shouldMask(board("impostor", "round"), false, false, false));
    }
}
