package com.thebirdhouse.plugin;

import net.runelite.api.Skill;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The rules that decide whether an item appearing in the inventory was earned.
 *
 * Auto-submission runs off RuneLite's loot tracker, which cannot see skilling at all, so
 * tiles asking for a stardust or a Molch pearl could never complete on their own. Reading
 * the inventory instead is easy; reading it honestly is the whole problem, because an
 * inventory has no idea whether a thing was mined or withdrawn from a bank.
 *
 * The answer is that a gain only counts when it is anchored to gathering experience, is
 * not a Take off the floor, and was not already claimed by the loot tracker. Those three
 * are pure functions precisely so they can be pinned here without a running client.
 */
public class GatheredTrackerTest {

    private static Map<Integer, Integer> inv(int... idsAndCounts) {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < idsAndCounts.length; i += 2) {
            m.put(idsAndCounts[i], idsAndCounts[i + 1]);
        }
        return m;
    }

    // ── The anchor ───────────────────────────────────────────────────────────────

    @Test
    public void experienceInTheSameTickVouchesForAGain() {
        assertTrue(GatheredTracker.anchored(100, 100));
    }

    @Test
    public void theAnchorSurvivesAShortLag() {
        // An arena ticket is dispensed around the tag rather than exactly on it, so the
        // reward is allowed to trail the experience that earned it.
        assertTrue(GatheredTracker.anchored(104, 100));
        assertTrue(GatheredTracker.anchored(105, 100));
    }

    @Test
    public void theAnchorGoesStale() {
        assertFalse(GatheredTracker.anchored(106, 100));
        assertFalse(GatheredTracker.anchored(500, 100));
    }

    @Test
    public void aBankWithdrawalIsNeverAnchored() {
        // The point of the whole design: withdrawing, buying and trading award no
        // experience, so there is no tick at which they can clear this gate.
        assertFalse(GatheredTracker.anchored(100, Integer.MIN_VALUE));
    }

    // ── Goods changing hands ─────────────────────────────────────────────────────

    @Test
    public void aWithdrawalAlongsideGatheringExperienceIsRefused() {
        // Herblore, Crafting, Construction and Runecraft are trained standing at a bank,
        // so their experience arrives at the exact moment withdrawals do. The anchor
        // cannot separate those two; the bank moving is what says which one happened.
        assertTrue(GatheredTracker.changedHands(100, 100));
    }

    @Test
    public void theBankIsAllowedToReportEitherSideOfTheInventory() {
        // Two container subscribers run in an undefined order, so the bank may report the
        // tick before or the tick after the inventory it emptied into.
        assertTrue(GatheredTracker.changedHands(100, 99));
        assertTrue(GatheredTracker.changedHands(100, 101));
    }

    @Test
    public void aBankOpenedEarlierDoesNotTaintALaterGather() {
        assertFalse(GatheredTracker.changedHands(100, 98));
        assertFalse(GatheredTracker.changedHands(500, 100));
    }

    @Test
    public void neverHavingBankedRefusesNothing() {
        assertFalse(GatheredTracker.changedHands(100, Integer.MIN_VALUE));
    }

    // ── What counts as experience ────────────────────────────────────────────────

    @Test
    public void experienceGoingUpVouchesForTheTick() {
        assertTrue(GatheredTracker.earned(1_000, 1_050));
    }

    @Test
    public void aDrainedLevelHealingBackVouchesForNothing() {
        // Karil drains Agility, so every point that comes back during a Barrows trip
        // reports a stat change with the experience sitting exactly where it was. Reading
        // those as gathering is what let a Moons piece arrive labelled "Agility".
        assertFalse(GatheredTracker.earned(1_000, 1_000));
    }

    @Test
    public void aBoostWearingOffVouchesForNothing() {
        assertFalse(GatheredTracker.earned(1_000, 1_000));
    }

    @Test
    public void theFirstSightingOfASkillIsOnlyABaseline() {
        // Login reports every skill at once. Treating that burst as earning would vouch
        // for whatever the first inventory change after logging in happened to be.
        assertFalse(GatheredTracker.earned(null, 13_034_431));
    }

    // ── Ground pickups ───────────────────────────────────────────────────────────

    @Test
    public void anItemTakenOffTheFloorIsRefused() {
        // A monster drop reaches the inventory via Take and the loot tracker has already
        // reported it. Refusing takes also stops the drop-it-and-pick-it-up exploit.
        assertTrue(GatheredTracker.fromGround(100, 100));
        assertTrue(GatheredTracker.fromGround(101, 100));
    }

    @Test
    public void anOldTakeDoesNotBlockALaterGather() {
        assertFalse(GatheredTracker.fromGround(102, 100));
    }

    @Test
    public void neverHavingTakenAnythingBlocksNothing() {
        assertFalse(GatheredTracker.fromGround(100, Integer.MIN_VALUE));
    }

    // ── Not paying twice ─────────────────────────────────────────────────────────

    @Test
    public void anItemTheLootTrackerClaimedIsSkipped() {
        Set<String> loot = new HashSet<>();
        loot.add("soaked page");
        assertTrue(GatheredTracker.alreadyReported("Soaked page", 100, 100, loot));
    }

    @Test
    public void theDuplicateCheckIsCaseInsensitive() {
        Set<String> loot = new HashSet<>();
        loot.add("stardust");
        assertTrue(GatheredTracker.alreadyReported("STARDUST", 100, 100, loot));
    }

    @Test
    public void aDifferentItemInTheSameLootIsStillCredited() {
        Set<String> loot = new HashSet<>();
        loot.add("shark");
        assertFalse(GatheredTracker.alreadyReported("Stardust", 100, 100, loot));
    }

    @Test
    public void anOldLootEventDoesNotSuppressANewGather() {
        Set<String> loot = new HashSet<>();
        loot.add("stardust");
        assertFalse(GatheredTracker.alreadyReported("Stardust", 100, 90, loot));
    }

    @Test
    public void noLootAtAllSuppressesNothing() {
        assertFalse(GatheredTracker.alreadyReported("Stardust", 100, Integer.MIN_VALUE, new HashSet<>()));
    }

    // ── The diff ─────────────────────────────────────────────────────────────────

    @Test
    public void aNewItemIsAGain() {
        Map<Integer, Integer> gained = GatheredTracker.gainedQuantities(inv(), inv(25547, 1));
        assertEquals(1, gained.size());
        assertEquals(Integer.valueOf(1), gained.get(25547));
    }

    @Test
    public void aGrowingStackCountsOnlyWhatWasAdded() {
        // What makes "get 2000 stardust" survivable: the stack delta is the quantity, so
        // one mining tick submits what it actually produced rather than a flat one.
        Map<Integer, Integer> gained = GatheredTracker.gainedQuantities(inv(25547, 120), inv(25547, 168));
        assertEquals(Integer.valueOf(48), gained.get(25547));
    }

    @Test
    public void spendingIsNotAGain() {
        Map<Integer, Integer> gained = GatheredTracker.gainedQuantities(inv(25547, 200), inv(25547, 50));
        assertTrue(gained.isEmpty());
    }

    @Test
    public void anUnchangedInventoryProducesNothing() {
        Map<Integer, Integer> before = inv(25547, 30, 1511, 12);
        assertTrue(GatheredTracker.gainedQuantities(before, inv(25547, 30, 1511, 12)).isEmpty());
    }

    @Test
    public void severalItemsCanArriveAtOnce() {
        Map<Integer, Integer> gained = GatheredTracker.gainedQuantities(
            inv(1511, 4), inv(1511, 9, 25547, 3));
        assertEquals(2, gained.size());
        assertEquals(Integer.valueOf(5), gained.get(1511));
        assertEquals(Integer.valueOf(3), gained.get(25547));
    }

    @Test
    public void movingItemsAroundIsNotAGain() {
        // Snapshots total by id rather than by slot, so rearranging the inventory or
        // splitting a stack across slots reads as no change at all.
        assertTrue(GatheredTracker.gainedQuantities(inv(25547, 60), inv(25547, 60)).isEmpty());
    }

    // ── How the submission is labelled ───────────────────────────────────────────

    @Test
    public void theSourceNamesTheActivity() {
        assertEquals("Mining", GatheredTracker.label(Skill.MINING));
        assertEquals("Hunter", GatheredTracker.label(Skill.HUNTER));
        assertEquals("Agility", GatheredTracker.label(Skill.AGILITY));
    }

    @Test
    public void anUnknownActivityStillHasAName() {
        assertEquals("Skilling", GatheredTracker.label(null));
    }

    @Test
    public void theSourceCanNeverSatisfyAKillCountTile() {
        // A kill-count tile matches on the source name, so labelling these with the skill
        // rather than an NPC is what stops "Kill 5 Yak" being completed by fishing.
        for (Skill skill : new Skill[]{Skill.MINING, Skill.FISHING, Skill.HUNTER, Skill.AGILITY}) {
            String source = GatheredTracker.label(skill);
            assertFalse("a skill name must not read as an NPC", source.isEmpty());
            assertEquals(source, GatheredTracker.label(skill));
        }
    }
}
