package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Board items that arrive without a loot event.
 *
 * Auto-submission is driven entirely by RuneLite's loot tracker, which only reports NPC
 * kills and an explicit list of loot events — chests, reward crates, raid and minigame
 * pools. Everything a player gathers is invisible to it: a stardust mined off a shooting
 * star, a Molch pearl from aerial fishing, a salamander out of a net trap and an agility
 * ticket from the arena all simply materialise in the inventory. Tiles asking for those
 * could never complete on their own, and players reasonably read the silence as the board
 * being broken rather than as the drop being untrackable.
 *
 * The gap is closed the same way {@link ClueTracker} closes it for clue containers: diff
 * the inventory, and only credit a gain that is pinned to something the player
 * demonstrably did. Here the anchor is gathering experience in the same handful of ticks.
 * That single rule is what keeps this honest, because the ways of acquiring an item
 * dishonestly — withdrawing it from a bank, buying it off the Grand Exchange or from a
 * shop, taking it in a trade — award no experience at all and so can never clear it.
 *
 * Ground pickups are refused for the two reasons they are refused in ClueTracker: a
 * monster drop reaches the inventory via Take and would otherwise be paid for twice, and
 * without the rule a player could drop a board item and pick it back up for as many
 * credits as they had patience for.
 *
 * Nothing here decides what a gain is worth. Matching, per-game-type rules, quantity caps
 * and the not-started warning all belong to {@link DropMatcher}, and a gathered item is
 * handed to exactly the same path a drop takes.
 */
@Slf4j
@Singleton
public class GatheredTracker {

    // ClueTracker owns the inventory diff for Clue Trail rooms, including its own rules
    // about jars and which containers may be credited. Running both over one room would
    // mean two trackers bidding on the same inventory change.
    private static final String CLUE_TRAIL_GAME_TYPE = "spire";

    /**
     * Skills whose experience vouches for an item appearing out of nowhere.
     *
     * Gathering skills are the point of the exercise. The production skills are here
     * because a board tile is just as legitimately satisfied by making the item as by
     * finding it, and because their experience is equally impossible to fake through a
     * bank or the Grand Exchange.
     *
     * Combat is deliberately absent. Loot from a kill is the loot tracker's job, and
     * accepting combat experience as an anchor would credit inventory changes that happen
     * to coincide with a fight without adding a single tile the tracker cannot already
     * see.
     */
    private static final Set<Skill> EARNING = EnumSet.of(
        Skill.MINING, Skill.FISHING, Skill.WOODCUTTING, Skill.HUNTER,
        Skill.FARMING, Skill.THIEVING, Skill.AGILITY, Skill.FIREMAKING,
        Skill.RUNECRAFT, Skill.HERBLORE, Skill.CRAFTING, Skill.CONSTRUCTION,
        Skill.SAILING
    );

    /**
     * How long an experience drop vouches for.
     *
     * Wider than the tick ClueTracker allows itself, because that one is matching a
     * container against the gather that produced it, whereas an activity reward can lag
     * the experience that earned it — an arena ticket is dispensed around the tag, not on
     * it. Widening costs nothing in honesty: the acquisitions this is meant to exclude
     * award no experience whatsoever, so no window length lets them through.
     */
    private static final int ANCHOR_TICKS = 5;

    private static final int RECENT_TICKS = 1;

    /**
     * Containers whose movement means an item changed hands rather than being gathered.
     *
     * A withdrawal, a completed trade and a collected offer each move one of these in the
     * same tick as the inventory, which is far more direct evidence than the experience
     * anchor ever was. Shops are left out deliberately: the game has a separate container
     * per shop and there are several hundred of them, and a shop is not somewhere anyone
     * trains, so the exposure does not justify guessing at that list.
     */
    private static final Set<Integer> VAULTS;

    static {
        Set<Integer> vaults = new HashSet<>(Arrays.asList(
            InventoryID.BANK, InventoryID.TRADEOFFER, InventoryID.DUELOFFER
        ));
        vaults.addAll(Arrays.asList(
            InventoryID.GE_OFFER_0, InventoryID.GE_OFFER_1, InventoryID.GE_OFFER_2,
            InventoryID.GE_OFFER_3, InventoryID.GE_OFFER_4, InventoryID.GE_OFFER_5,
            InventoryID.GE_OFFER_6, InventoryID.GE_OFFER_7
        ));
        VAULTS = vaults;
    }

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private DropMatcher dropMatcher;

    /** Last known inventory as item id to quantity, so a change can be read as a diff. */
    private Map<Integer, Integer> lastInventory = new HashMap<>();

    /**
     * Whether a baseline has been taken. The first container change after a login or a
     * reset reports a full inventory against an empty map, which is every item you are
     * carrying looking like it was just acquired.
     */
    private boolean primed = false;

    private int lastEarnTick = Integer.MIN_VALUE;
    private Skill lastEarnSkill = null;

    /**
     * Experience last seen per skill, so a stat change can be read as earning or not.
     *
     * A drained level healing back reports as a stat change with the experience sitting
     * exactly where it was. Karil drains Agility, so a Barrows trip alone fires one of
     * those every time a point comes back.
     */
    private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
    private int lastTakeTick = Integer.MIN_VALUE;

    /**
     * When a bank, trade or shop last moved, so goods changing hands can be told from a
     * gather.
     *
     * The experience anchor was meant to carry this on its own, on the reasoning that a
     * withdrawal earns none. That holds for mining and fishing, but Herblore, Crafting,
     * Construction and Runecraft are trained standing at a bank, so their experience is
     * arriving continuously at the exact moment withdrawals happen.
     */
    private int lastVaultTick = Integer.MIN_VALUE;
    private int lastLootTick = Integer.MIN_VALUE;
    private Set<String> lastLootItems = new HashSet<>();

    private final List<Gain> pending = new ArrayList<>();
    private int pendingTick = Integer.MIN_VALUE;
    private Skill pendingSkill = null;

    /** An item that appeared in the inventory. */
    static final class Gain {
        final int itemId;
        final String name;
        final int quantity;

        Gain(int itemId, String name, int quantity) {
            this.itemId = itemId;
            this.name = name;
            this.quantity = quantity;
        }
    }

    public void reset() {
        lastInventory = new HashMap<>();
        primed = false;
        lastEarnTick = Integer.MIN_VALUE;
        lastEarnSkill = null;
        lastXp.clear();
        lastTakeTick = Integer.MIN_VALUE;
        lastVaultTick = Integer.MIN_VALUE;
        lastLootTick = Integer.MIN_VALUE;
        lastLootItems = new HashSet<>();
        pending.clear();
        pendingTick = Integer.MIN_VALUE;
        pendingSkill = null;
    }

    private boolean inTrackedRoom() {
        if (!config.autoSubmitGathered()) return false;
        BoardData board = dropMatcher.getActiveBoard();
        return board != null
            && !CLUE_TRAIL_GAME_TYPE.equals(board.getGameType())
            && !dropMatcher.isEventOver();
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        if (!EARNING.contains(skill)) return;
        Integer seen = lastXp.put(skill, event.getXp());
        if (!earned(seen, event.getXp())) return;
        lastEarnTick = client.getTickCount();
        lastEarnSkill = skill;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        String option = event.getMenuOption();
        if ("Take".equals(option) || "Take-all".equals(option)) {
            lastTakeTick = client.getTickCount();
        }
    }

    /** Remembers what the loot tracker already reported, so it is never paid for twice. */
    @Subscribe
    public void onLootReceived(LootReceived event) {
        lastLootTick = client.getTickCount();
        lastLootItems = new HashSet<>();
        for (ItemStack stack : event.getItems()) {
            String name = itemName(stack.getId());
            if (name != null) lastLootItems.add(name.toLowerCase());
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (VAULTS.contains(event.getContainerId())) {
            lastVaultTick = client.getTickCount();
            return;
        }
        if (event.getContainerId() != InventoryID.INV) return;

        Map<Integer, Integer> current = snapshot(event.getItemContainer());
        Map<Integer, Integer> previous = lastInventory;
        lastInventory = current;

        if (!primed) {
            primed = true;
            return;
        }
        if (!inTrackedRoom()) return;

        int tick = client.getTickCount();
        if (!anchored(tick, lastEarnTick)) {
            // Nothing was being earned just now, so this came out of a bank, a shop, the
            // Grand Exchange, a trade or a reclaim rather than off a rock, a tree or a trap.
            return;
        }
        if (fromGround(tick, lastTakeTick)) {
            log.debug("[Birdhouse] Ignoring inventory gain picked up off the ground");
            return;
        }

        List<Gain> gains = gains(previous, current);
        if (gains.isEmpty()) return;

        // Held for a tick rather than submitted here. The loot tracker reports off this
        // same inventory change and the order two subscribers run in is undefined, so
        // asking now whether it has already claimed the item would be a coin flip.
        pending.addAll(gains);
        pendingTick = tick;
        pendingSkill = lastEarnSkill;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (pending.isEmpty()) return;
        if (client.getTickCount() == pendingTick) return;

        List<Gain> due = new ArrayList<>(pending);
        Skill skill = pendingSkill;
        int found = pendingTick;
        pending.clear();
        pendingSkill = null;

        if (!inTrackedRoom()) return;

        // Asked here rather than as the change arrives, for the same reason the loot
        // tracker is: the order two container subscribers run in is undefined, so the
        // bank may not have reported yet at the moment the inventory does.
        if (changedHands(found, lastVaultTick)) {
            log.debug("[Birdhouse] Ignoring inventory gain that came out of a bank, trade or shop");
            return;
        }

        String source = label(skill);
        for (Gain gain : due) {
            if (alreadyReported(gain.name, found, lastLootTick, lastLootItems)) {
                log.debug("[Birdhouse] '{}' already reported by the loot tracker", gain.name);
                continue;
            }
            log.info("[Birdhouse] Gathered {} x{} ({})", gain.name, gain.quantity, source);
            dropMatcher.handleGatheredItem(source, gain.name, gain.itemId, gain.quantity);
        }
    }

    // ── Decisions, kept pure so they can be tested without a client ──────────────

    /**
     * Whether a stat change carried experience, given what the skill last sat at.
     *
     * A stat change on its own says nothing. A drained level healing back, a boost wearing
     * off and the burst that arrives at login all report one with the experience unmoved,
     * and each would otherwise open the window that is supposed to keep bank withdrawals,
     * trades and Grand Exchange collections out. The first sighting of a skill is the
     * baseline rather than a gain, so logging in never vouches for anything.
     */
    static boolean earned(Integer seen, int xp) {
        return seen != null && xp > seen;
    }

    /** Whether experience earned at {@code lastEarnTick} still vouches for {@code tick}. */
    static boolean anchored(int tick, int lastEarnTick) {
        if (lastEarnTick == Integer.MIN_VALUE) return false;
        return tick - lastEarnTick <= ANCHOR_TICKS && tick >= lastEarnTick;
    }

    /**
     * Whether a bank, trade or shop moved close enough to have been where the item came
     * from.
     *
     * This is the guard the experience anchor was assumed to provide and does not, since
     * the production skills are trained at a bank.
     */
    static boolean changedHands(int tick, int lastVaultTick) {
        if (lastVaultTick == Integer.MIN_VALUE) return false;
        return Math.abs(tick - lastVaultTick) <= RECENT_TICKS;
    }

    /** Whether the change is close enough to a Take to have come off the floor. */
    static boolean fromGround(int tick, int lastTakeTick) {
        if (lastTakeTick == Integer.MIN_VALUE) return false;
        return tick - lastTakeTick <= RECENT_TICKS;
    }

    /** Whether the loot tracker has already claimed this item around the same tick. */
    static boolean alreadyReported(String name, int foundTick, int lootTick, Set<String> lootItems) {
        if (name == null || lootItems == null || lootItems.isEmpty()) return false;
        if (lootTick == Integer.MIN_VALUE) return false;
        return lootTick >= foundTick - RECENT_TICKS && lootItems.contains(name.toLowerCase());
    }

    /** Items whose quantity went up, as ids paired with how many appeared. */
    static Map<Integer, Integer> gainedQuantities(Map<Integer, Integer> before, Map<Integer, Integer> after) {
        Map<Integer, Integer> gained = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : after.entrySet()) {
            int delta = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (delta > 0) gained.put(e.getKey(), delta);
        }
        return gained;
    }

    static String label(Skill skill) {
        if (skill == null) return "Skilling";
        String name = skill.getName();
        if (name == null || name.isEmpty()) return "Skilling";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase();
    }

    // ── Client-facing helpers ────────────────────────────────────────────────────

    private List<Gain> gains(Map<Integer, Integer> before, Map<Integer, Integer> after) {
        List<Gain> list = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : gainedQuantities(before, after).entrySet()) {
            String name = itemName(e.getKey());
            if (name == null || name.isEmpty()) continue;
            list.add(new Gain(e.getKey(), name, e.getValue()));
        }
        return list;
    }

    private String itemName(int itemId) {
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            return comp == null ? null : comp.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<Integer, Integer> snapshot(ItemContainer container) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (container == null) return counts;
        for (Item item : container.getItems()) {
            if (item == null || item.getId() < 0) continue;
            counts.merge(item.getId(), Math.max(1, item.getQuantity()), Integer::sum);
        }
        return counts;
    }
}
