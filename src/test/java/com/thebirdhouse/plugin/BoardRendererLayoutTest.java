package com.thebirdhouse.plugin;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The tile race track snakes, and this is the arithmetic that makes it snake.
 *
 * The board used to be drawn as straight rows, which mirrored every odd row against the
 * website. Positions were still correct underneath, so nothing looked broken — but the
 * path read wrongly. Standing at the right-hand end of a row, the next tile appeared to
 * be the one directly below when it was really the one at the far left.
 *
 * The rule itself, and the column counts it runs against, are checked on the server side
 * in functions/test/tileRaceLayout.test.js. This covers the plugin's copy, because the
 * two have to agree for the boards to look the same.
 */
public class BoardRendererLayoutTest {
    /** Column counts the server sends, from the website's getBoardLayout. */
    private static final int[] WIDTHS = {5, 8, 10, 12};

    @Test
    public void firstRowRunsLeftToRight() {
        assertEquals(0, BoardRenderer.serpentineSlot(0, 10));
        assertEquals(4, BoardRenderer.serpentineSlot(4, 10));
        assertEquals(9, BoardRenderer.serpentineSlot(9, 10));
    }

    @Test
    public void secondRowRunsBackwards() {
        // Straight rows would have put tile 10 at the left edge of its row (slot 10) and
        // tile 19 at the right (slot 19). Snaking turns that around.
        assertEquals(19, BoardRenderer.serpentineSlot(10, 10));
        assertEquals(10, BoardRenderer.serpentineSlot(19, 10));
    }

    @Test
    public void theThirdRowTurnsBackAgain() {
        assertEquals(20, BoardRenderer.serpentineSlot(20, 10));
        assertEquals(29, BoardRenderer.serpentineSlot(29, 10));
    }

    @Test
    public void aRowEndsDirectlyAboveTheNextRowsStart() {
        // The turn that was misread on the 42-tile board: tile 19 finishes the second row
        // at the left edge, and tile 20 sits immediately beneath it rather than across
        // the board.
        int cols = 10;
        int last = BoardRenderer.serpentineSlot(19, cols);
        int next = BoardRenderer.serpentineSlot(20, cols);
        assertEquals("the row turn should drop straight down", last + cols, next);
    }

    @Test
    public void consecutiveTilesAlwaysTouch() {
        // The whole point of a snake: following the path never needs the tile numbers,
        // because the next tile is always the one beside you or the one below.
        for (int cols : WIDTHS) {
            for (int position = 1; position < cols * 6; position++) {
                int prev = BoardRenderer.serpentineSlot(position - 1, cols);
                int here = BoardRenderer.serpentineSlot(position, cols);
                boolean sideBySide = (prev / cols == here / cols) && Math.abs(prev - here) == 1;
                boolean steppedDown = here == prev + cols;
                assertTrue(
                    "tile " + position + " on a " + cols + "-wide board jumps from square "
                        + prev + " to " + here,
                    sideBySide || steppedDown);
            }
        }
    }

    @Test
    public void everySquareIsUsedExactlyOnce() {
        for (int cols : WIDTHS) {
            int total = cols * 6;
            Map<Integer, Integer> taken = new HashMap<>();
            for (int position = 0; position < total; position++) {
                int slot = BoardRenderer.serpentineSlot(position, cols);
                assertTrue("square " + slot + " is off the board", slot >= 0 && slot < total);
                assertFalse(
                    "tiles " + taken.get(slot) + " and " + position + " share square " + slot,
                    taken.containsKey(slot));
                taken.put(slot, position);
            }
        }
    }

    @Test
    public void aSingleColumnBoardStillWalksDownwards() {
        // Degenerate but reachable: a one-tile board clamps the width to 1, and a width of
        // one makes every row both the first and last column. The mirror must be a no-op
        // rather than a negative column.
        for (int position = 0; position < 5; position++) {
            assertEquals(position, BoardRenderer.serpentineSlot(position, 1));
        }
    }
}
