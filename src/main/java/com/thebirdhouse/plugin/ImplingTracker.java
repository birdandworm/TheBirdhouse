package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clue Trail support for implings, which the loot tracker cannot see.
 *
 * Two paths exist in game and only one of them produces a loot event:
 *
 *   - Caught with no jar: the loot is handed over immediately and RuneLite files it as
 *     NPC loot named after the impling, so DropMatcher already handles it.
 *   - Caught into a jar: nothing is looted yet. Opening the jar later is a plain inventory
 *     interaction, and the loot tracker has no handler for impling jars, so a clue won
 *     that way is invisible to the backend.
 *
 * That gap is what makes bought jars indistinguishable from hunted ones. This tracker
 * closes it from both ends: it reports each catch as it happens, and it manufactures the
 * clue submission when a jar is opened. The backend pairs the two, so a jar bought off the
 * Grand Exchange has no matching catch and earns nothing.
 *
 * Everything here is scoped to Clue Trail rooms; other game types are untouched.
 */
@Slf4j
@Singleton
public class ImplingTracker {

    private static final String SPIRE_GAME_TYPE = "spire";

    // "You manage to catch the impling and squeeze it into a jar." The other catch message
    // ("...and acquire some loot") is the no-jar case, which arrives as NPC loot instead.
    private static final Pattern JAR_CATCH_MESSAGE = Pattern.compile(
        "you manage to catch the impling and squeeze it into a jar", Pattern.CASE_INSENSITIVE
    );

    // "Eclectic impling jar" -> "Eclectic impling". Empty jars carry no impling name and so
    // never match, which is what we want: an empty jar is not a catch.
    private static final Pattern JAR_ITEM = Pattern.compile("^(.*impling) jar$", Pattern.CASE_INSENSITIVE);

    private static final Pattern CLUE_CONTAINER = Pattern.compile(
        "^(?:clue scroll|scroll box|clue geode|clue bottle|clue nest) \\((beginner|easy|medium|hard|elite|master)\\)$",
        Pattern.CASE_INSENSITIVE
    );

    @Inject
    private Client client;

    @Inject
    private net.runelite.client.game.ItemManager itemManager;

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private DropMatcher dropMatcher;

    // Last known inventory, by item name, so a change can be read as a diff.
    private Map<String, Integer> lastInventory = new HashMap<>();
    // Set by the catch message and consumed by the inventory change in the same tick, which
    // is the only place the impling's type is actually stated.
    private boolean awaitingCatch = false;

    public void reset() {
        lastInventory = new HashMap<>();
        awaitingCatch = false;
    }

    private boolean inClueTrailRoom() {
        BoardData board = dropMatcher.getActiveBoard();
        return board != null && SPIRE_GAME_TYPE.equals(board.getGameType())
            && !Boolean.FALSE.equals(board.getStarted());
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!inClueTrailRoom()) return;
        Matcher m = JAR_CATCH_MESSAGE.matcher(event.getMessage());
        if (m.find()) {
            // The message names no impling, so the type is read from the jar that appears.
            awaitingCatch = true;
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
            String jarred = firstGainedJar(previous, current);
            if (jarred != null) {
                log.info("[Birdhouse] Impling caught into a jar: {}", jarred);
                apiClient.reportImplingCatch(dropMatcher.getActiveRoomCode(), jarred, 1);
            }
        }

        String openedJar = firstLostJar(previous, current);
        if (openedJar == null) return;
        String clue = firstGainedClue(previous, current);
        if (clue == null) return;

        // Submitted with the jar suffix so the backend can tell this apart from a clue
        // looted straight off a caught impling, which needs no catch credit.
        log.info("[Birdhouse] Clue from impling jar: {} from {}", clue, openedJar);
        submitJarClue(openedJar, clue);
    }

    private Map<String, Integer> snapshot(ItemContainer container) {
        Map<String, Integer> counts = new HashMap<>();
        if (container == null) return counts;
        for (Item item : container.getItems()) {
            if (item == null || item.getId() < 0) continue;
            try {
                ItemComposition comp = itemManager.getItemComposition(item.getId());
                String name = comp.getName();
                if (name == null || name.isEmpty()) continue;
                counts.merge(name, Math.max(1, item.getQuantity()), Integer::sum);
            } catch (Exception ignored) {
                // Unknown item id — nothing we can name, so nothing we can match.
            }
        }
        return counts;
    }

    private String firstGainedJar(Map<String, Integer> before, Map<String, Integer> after) {
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (e.getValue() <= before.getOrDefault(e.getKey(), 0)) continue;
            Matcher m = JAR_ITEM.matcher(e.getKey());
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    private String firstLostJar(Map<String, Integer> before, Map<String, Integer> after) {
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            if (e.getValue() <= after.getOrDefault(e.getKey(), 0)) continue;
            Matcher m = JAR_ITEM.matcher(e.getKey());
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    private String firstGainedClue(Map<String, Integer> before, Map<String, Integer> after) {
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (e.getValue() <= before.getOrDefault(e.getKey(), 0)) continue;
            if (CLUE_CONTAINER.matcher(e.getKey()).matches()) return e.getKey();
        }
        return null;
    }

    private void submitJarClue(String impling, String clueName) {
        String roomCode = dropMatcher.getActiveRoomCode();
        if (roomCode == null || roomCode.isEmpty()) return;
        Matcher m = CLUE_CONTAINER.matcher(clueName);
        if (!m.matches()) return;
        String tier = m.group(1).toLowerCase();

        ProofPayload payload = new ProofPayload();
        payload.setRoomCode(roomCode);
        payload.setPlayerName(client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null);
        payload.setTileKey("spire~key~" + tier);
        payload.setTileName(clueName);
        payload.setGameType(SPIRE_GAME_TYPE);
        payload.setItemName(clueName);
        payload.setNpcName(impling + " (jar)");
        payload.setQuantity(1);
        payload.setTimestamp(System.currentTimeMillis());

        // No screenshot: opening a jar in a bank is not proof of anything, and the backend
        // gates this on the catch counter rather than on an image.
        apiClient.submitProof(payload, null);
    }
}
