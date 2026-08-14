package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clue Trail support for the clue sources RuneLite's loot tracker cannot see.
 *
 * Every loot event the tracker emits is anchored to an NPC death, a player kill, the
 * pickpocket message, or a menu click on one of an explicit list of containers. Two ways of
 * winning a clue fall outside all of that and so were invisible to the backend:
 *
 *   - Impling jars. Catching an impling into a jar loots nothing, and opening the jar later
 *     is a plain inventory interaction with no handler. Only a no-jar catch, which loots
 *     immediately as NPC loot, was ever reported. That left honest jarred catches uncounted
 *     while making jars bought off the Grand Exchange indistinguishable from hunted ones.
 *   - Skilling. Mining, Fishing and Woodcutting emit no loot events whatsoever, so a scroll
 *     box, geode, bottle or nest simply materialises in the inventory unannounced.
 *
 * Both are recovered here by diffing the inventory, and both are pinned to something the
 * player demonstrably did — a catch message, or gathering experience in the same tick.
 *
 * Ground pickups are deliberately never credited, which is what keeps this honest. Monster
 * drops land on the floor and enter the inventory via Take, so ignoring takes prevents
 * paying twice for a drop the loot tracker already reported, and stops the obvious exploit
 * of dropping a scroll box and picking it back up for unlimited credits.
 *
 * Everything here is scoped to Clue Trail rooms; other game types are untouched.
 */
@Slf4j
@Singleton
public class ClueTracker {

    private static final String SPIRE_GAME_TYPE = "spire";

    // "You manage to catch the impling and squeeze it into a jar." The other catch message
    // ("...and acquire some loot") is the no-jar case, which arrives as NPC loot instead.
    private static final Pattern JAR_CATCH_MESSAGE = Pattern.compile(
        "you manage to catch the impling and squeeze it into a jar", Pattern.CASE_INSENSITIVE
    );

    // "Eclectic impling jar" -> "Eclectic impling". Empty jars carry no impling name and so
    // never match, which is what we want: an empty jar is not a catch.
    private static final Pattern JAR_ITEM = Pattern.compile("^(.*impling) jar$", Pattern.CASE_INSENSITIVE);

    private static final String TIERS = "beginner|easy|medium|hard|elite|master";

    // What a jar yields: a scroll box after X Marks the Spot, a bare clue scroll before it.
    private static final Pattern JAR_CLUE = Pattern.compile(
        "^(?:clue scroll|scroll box) \\((" + TIERS + ")\\)$", Pattern.CASE_INSENSITIVE
    );

    // What skilling yields. Bare clue scrolls are excluded on purpose: a scroll appearing
    // means a container was opened, which must not pay a second time, and opening one that
    // was banked before the event must never pay at all.
    private static final Pattern SKILLING_CONTAINER = Pattern.compile(
        "^(?:scroll box|clue geode|clue bottle|clue nest) \\((" + TIERS + ")\\)$", Pattern.CASE_INSENSITIVE
    );

    private static final Set<Skill> GATHERING = EnumSet.of(Skill.MINING, Skill.FISHING, Skill.WOODCUTTING);

    // Experience arrives on the same tick as the container, but a tick of slack costs
    // nothing and covers the odd ordering difference.
    private static final int GATHER_TICKS = 2;
    private static final int RECENT_TICKS = 1;

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private DropMatcher dropMatcher;

    // Last known inventory by item name, so a change can be read as a diff.
    private Map<String, Integer> lastInventory = new HashMap<>();
    // Set by the catch message and consumed by the inventory change in the same tick, which
    // is the only place the impling's type is actually stated.
    private boolean awaitingCatch = false;

    private int lastGatherTick = Integer.MIN_VALUE;
    private Skill lastGatherSkill = null;
    private int lastTakeTick = Integer.MIN_VALUE;
    private int lastLootTick = Integer.MIN_VALUE;
    private Set<String> lastLootItems = new HashSet<>();

    public void reset() {
        lastInventory = new HashMap<>();
        awaitingCatch = false;
        lastGatherTick = Integer.MIN_VALUE;
        lastGatherSkill = null;
        lastTakeTick = Integer.MIN_VALUE;
        lastLootTick = Integer.MIN_VALUE;
        lastLootItems = new HashSet<>();
    }

    private boolean inClueTrailRoom() {
        BoardData board = dropMatcher.getActiveBoard();
        return board != null && SPIRE_GAME_TYPE.equals(board.getGameType())
            && !Boolean.FALSE.equals(board.getStarted());
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!inClueTrailRoom()) return;
        if (JAR_CATCH_MESSAGE.matcher(event.getMessage()).find()) {
            // The message names no impling, so the type is read from the jar that appears.
            awaitingCatch = true;
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        if (GATHERING.contains(event.getSkill())) {
            lastGatherTick = client.getTickCount();
            lastGatherSkill = event.getSkill();
        }
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
        if (event.getContainerId() != InventoryID.INV) return;

        Map<String, Integer> current = snapshot(event.getItemContainer());
        Map<String, Integer> previous = lastInventory;
        lastInventory = current;

        if (!inClueTrailRoom()) return;

        if (awaitingCatch) {
            awaitingCatch = false;
            String jarred = firstGained(previous, current, JAR_ITEM);
            if (jarred != null) {
                log.info("[Birdhouse] Impling caught into a jar: {}", jarred);
                apiClient.reportImplingCatch(dropMatcher.getActiveRoomCode(), jarred, 1);
            }
        }

        String openedJar = firstLost(previous, current, JAR_ITEM);
        if (openedJar != null) {
            String clue = firstGainedMatching(previous, current, JAR_CLUE);
            if (clue != null) {
                // Tagged as a jar so the backend can require a recorded catch, which is what
                // separates a night of hunting from a stack of bought jars.
                log.info("[Birdhouse] Clue from impling jar: {} from {}", clue, openedJar);
                submitClue(openedJar + " (jar)", clue, JAR_CLUE);
            }
            // A jar was consumed, so this change is accounted for and is not a skilling find.
            return;
        }

        String gathered = firstGainedMatching(previous, current, SKILLING_CONTAINER);
        if (gathered == null) return;

        int tick = client.getTickCount();
        if (tick - lastGatherTick > GATHER_TICKS) {
            // No gathering experience just now, so this came from a bank, a trade, a reward
            // interface or an item reclaim rather than from a rock, a fish or a tree.
            return;
        }
        if (tick - lastTakeTick <= RECENT_TICKS) {
            log.debug("[Birdhouse] Ignoring '{}' picked up off the ground", gathered);
            return;
        }
        if (tick - lastLootTick <= RECENT_TICKS && lastLootItems.contains(gathered.toLowerCase())) {
            log.debug("[Birdhouse] '{}' already reported by the loot tracker", gathered);
            return;
        }

        String skill = lastGatherSkill == null ? "Skilling" : label(lastGatherSkill);
        log.info("[Birdhouse] Clue from {}: {}", skill, gathered);
        submitClue(skill, gathered, SKILLING_CONTAINER);
    }

    private static String label(Skill skill) {
        String name = skill.getName();
        if (name == null || name.isEmpty()) return "Skilling";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase();
    }

    private String itemName(int itemId) {
        try {
            ItemComposition comp = itemManager.getItemComposition(itemId);
            return comp == null ? null : comp.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Integer> snapshot(ItemContainer container) {
        Map<String, Integer> counts = new HashMap<>();
        if (container == null) return counts;
        for (Item item : container.getItems()) {
            if (item == null || item.getId() < 0) continue;
            String name = itemName(item.getId());
            if (name == null || name.isEmpty()) continue;
            counts.merge(name, Math.max(1, item.getQuantity()), Integer::sum);
        }
        return counts;
    }

    /** First item gained whose name matches, returning capture group 1. */
    private String firstGained(Map<String, Integer> before, Map<String, Integer> after, Pattern pattern) {
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (e.getValue() <= before.getOrDefault(e.getKey(), 0)) continue;
            Matcher m = pattern.matcher(e.getKey());
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    /** First item gained whose name matches, returning the whole item name. */
    private String firstGainedMatching(Map<String, Integer> before, Map<String, Integer> after, Pattern pattern) {
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (e.getValue() <= before.getOrDefault(e.getKey(), 0)) continue;
            if (pattern.matcher(e.getKey()).matches()) return e.getKey();
        }
        return null;
    }

    private String firstLost(Map<String, Integer> before, Map<String, Integer> after, Pattern pattern) {
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            if (e.getValue() <= after.getOrDefault(e.getKey(), 0)) continue;
            Matcher m = pattern.matcher(e.getKey());
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    private void submitClue(String source, String clueName, Pattern tierPattern) {
        String roomCode = dropMatcher.getActiveRoomCode();
        if (roomCode == null || roomCode.isEmpty()) return;
        Matcher m = tierPattern.matcher(clueName);
        if (!m.matches()) return;
        String tier = m.group(1).toLowerCase();

        ProofPayload payload = new ProofPayload();
        payload.setRoomCode(roomCode);
        payload.setPlayerName(client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null);
        payload.setTileKey("spire~key~" + tier);
        payload.setTileName(clueName);
        payload.setGameType(SPIRE_GAME_TYPE);
        payload.setItemName(clueName);
        payload.setNpcName(source);
        payload.setQuantity(1);
        payload.setTimestamp(System.currentTimeMillis());

        // No screenshot: an inventory change is not proof of anything, and the backend gates
        // these on the catch counter and the gathering anchor rather than on an image.
        apiClient.submitProof(payload, null);
    }
}
