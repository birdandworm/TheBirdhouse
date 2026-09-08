package com.thebirdhouse.plugin;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Crediting a kill-count tile from the game's own announcement.
 *
 * A kill-count tile is normally satisfied by a loot event named after the boss, which
 * quietly fails for the bosses that pay out through something else — the tracker books
 * Wintertodt's crates as "Supply crate (Wintertodt)", so a tile asking for "Wintertodt"
 * saw nothing at all despite the kill being real and announced.
 *
 * The risk in reading the announcement instead is the opposite one: most bosses both
 * announce and drop, and crediting each would double every kill. These pin the parse and
 * the rule that decides which announcements are already accounted for.
 */
public class KillCountTrackerTest {

    private static Map<String, Integer> loot(String name, int tick) {
        Map<String, Integer> m = new HashMap<>();
        m.put(name.toLowerCase(), tick);
        return m;
    }

    // ── Reading the message ──────────────────────────────────────────────────────

    @Test
    public void wintertodtIsSubdued() {
        // The phrasing this whole change exists for.
        assertEquals("Wintertodt", KillCounts.bossFrom("Your subdued Wintertodt count is: 41"));
    }

    @Test
    public void anOrdinaryBossIsKilled() {
        assertEquals("Vorkath", KillCounts.bossFrom("Your Vorkath kill count is: 312"));
    }

    @Test
    public void temporossIsAnOrdinaryCount() {
        assertEquals("Tempoross", KillCounts.bossFrom("Your Tempoross kill count is: 8"));
    }

    @Test
    public void raidsAnnounceACompletion() {
        assertEquals("Chambers of Xeric",
            KillCounts.bossFrom("Your completed Chambers of Xeric count is: 27"));
    }

    @Test
    public void theGauntletAnnouncesACompletionCount() {
        assertEquals("Corrupted Gauntlet",
            KillCounts.bossFrom("Your completion count for Corrupted Gauntlet is: 5"));
    }

    @Test
    public void countsSurviveColourTags() {
        assertEquals("Zulrah",
            KillCounts.bossFrom("Your <col=ff0000>Zulrah</col> kill count is: <col=ff0000>1,024"));
    }

    @Test
    public void aLapCountIsACount() {
        assertEquals("Ardougne Rooftop Course",
            KillCounts.bossFrom("Your Ardougne Rooftop Course lap count is: 150"));
    }

    @Test
    public void ordinaryChatIsNotAKillCount() {
        assertNull(KillCounts.bossFrom("Your bank is full."));
        assertNull(KillCounts.bossFrom("Oh dear, you are dead!"));
        assertNull(KillCounts.bossFrom(""));
        assertNull(KillCounts.bossFrom(null));
    }

    // ── Not crediting the same kill twice ────────────────────────────────────────

    @Test
    public void aBossThatDroppedLootIsNotCreditedAgain() {
        // Vorkath announces a count and drops loot under its own name. The loot already
        // credited the tile, so the announcement must not.
        assertTrue(KillCountTracker.creditedByLoot("Vorkath", 500, loot("Vorkath", 500)));
    }

    @Test
    public void theSuppressionToleratesATickEitherWay() {
        assertTrue(KillCountTracker.creditedByLoot("Vorkath", 502, loot("Vorkath", 500)));
        assertTrue(KillCountTracker.creditedByLoot("Vorkath", 498, loot("Vorkath", 500)));
    }

    @Test
    public void anEarlierKillDoesNotSuppressALaterOne() {
        // Second Vorkath of the trip: the loot from the first is long gone.
        assertFalse(KillCountTracker.creditedByLoot("Vorkath", 600, loot("Vorkath", 500)));
    }

    @Test
    public void wintertodtIsCreditedBecauseItsLootIsNamedDifferently() {
        // The entire point. The crate arrives as "Supply crate (Wintertodt)", which never
        // matches the boss, so nothing suppresses the announcement.
        Map<String, Integer> lootTicks = loot("Supply crate (Wintertodt)", 500);
        assertFalse(KillCountTracker.creditedByLoot("Wintertodt", 500, lootTicks));
    }

    @Test
    public void temporossIsCreditedForTheSameReason() {
        assertFalse(KillCountTracker.creditedByLoot("Tempoross", 500, loot("Reward pool (Tempoross)", 500)));
    }

    @Test
    public void anotherBossesLootSuppressesNothing() {
        assertFalse(KillCountTracker.creditedByLoot("Wintertodt", 500, loot("Vorkath", 500)));
    }

    @Test
    public void noLootAtAllSuppressesNothing() {
        assertFalse(KillCountTracker.creditedByLoot("Wintertodt", 500, new HashMap<>()));
        assertFalse(KillCountTracker.creditedByLoot("Wintertodt", 500, null));
        assertFalse(KillCountTracker.creditedByLoot(null, 500, loot("Vorkath", 500)));
    }

    @Test
    public void suppressionIsCaseInsensitive() {
        assertTrue(KillCountTracker.creditedByLoot("VORKATH", 500, loot("vorkath", 500)));
    }
}
