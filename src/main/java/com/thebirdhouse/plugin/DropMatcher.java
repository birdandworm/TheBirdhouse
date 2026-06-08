package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPCComposition;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Matches received drops against the player's active board tiles.
 * When a match is found, triggers proof submission.
 */
@Slf4j
@Singleton
public class DropMatcher {

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private ScreenshotHelper screenshotHelper;

    @Inject
    private BirdhouseOverlay overlay;

    private BoardData activeBoard;
    private String activeRoomCode;

    public BoardData getActiveBoard() {
        return activeBoard;
    }

    public void loadActiveBoard(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return;
        }
        this.activeRoomCode = roomCode;
        apiClient.fetchBoard(roomCode).thenAccept(board -> {
            if (board != null) {
                this.activeBoard = board;
                log.info("Loaded board for room {}: {} tiles", roomCode, board.getTiles().size());
            }
        });
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event) {
        if (!config.autoSubmitDrops() || activeBoard == null) {
            return;
        }

        NPCComposition npcComp = event.getComposition();
        String npcName = npcComp != null ? npcComp.getName() : "Unknown";

        for (ItemStack itemStack : event.getItems()) {
            ItemComposition itemComp = itemManager.getItemComposition(itemStack.getId());
            String itemName = itemComp.getName();
            int quantity = itemStack.getQuantity();

            List<TileMatch> matches = findMatches(npcName, itemName, quantity);
            for (TileMatch match : matches) {
                submitMatch(match, npcName, itemName, quantity);
            }
        }
    }

    private List<TileMatch> findMatches(String npcName, String itemName, int quantity) {
        List<TileMatch> matches = new ArrayList<>();
        if (activeBoard == null || activeBoard.getTiles() == null) {
            return matches;
        }

        for (BoardTile tile : activeBoard.getTiles()) {
            if (tile.isCompleted()) continue;
            if (tile.getSpecial() != null) continue;

            // For tile race, only match the tile the player is currently on
            if ("tilerace".equals(tile.getGameType()) && !tile.isCurrent()) continue;

            // For chip drop, only match available (unlocked) tiles
            if ("chipdrop".equals(tile.getGameType()) && !tile.isAvailable()) continue;

            if (matchesTile(tile, npcName, itemName)) {
                matches.add(new TileMatch(tile.getKey(), tile.getName(), tile.getGameType()));
            }
        }
        return matches;
    }

    private boolean matchesTile(BoardTile tile, String npcName, String itemName) {
        String matchTarget = tile.getMatchName();
        if (matchTarget == null || matchTarget.isEmpty()) {
            return false;
        }

        String target = matchTarget.toLowerCase();
        String item = itemName.toLowerCase();
        String npc = npcName != null ? npcName.toLowerCase() : "";

        // Exact item name match
        if (target.equals(item)) return true;

        // "Boss - Item" format (e.g. "Zulrah - Tanzanite fang")
        if (target.contains(" - ")) {
            String[] parts = target.split(" - ", 2);
            if (npc.equals(parts[0].trim()) && item.equals(parts[1].trim())) return true;
        }

        // Boss name only match (any unique from this boss)
        if (target.equals(npc) && tile.isAnyUnique()) return true;

        return false;
    }

    private void submitMatch(TileMatch match, String npcName, String itemName, int quantity) {
        String playerName = client.getLocalPlayer().getName();

        ProofPayload payload = new ProofPayload();
        payload.setRoomCode(activeRoomCode);
        payload.setPlayerName(playerName);
        payload.setTileKey(match.getTileKey());
        payload.setTileName(match.getTileName());
        payload.setGameType(match.getGameType());
        payload.setItemName(itemName);
        payload.setNpcName(npcName);
        payload.setQuantity(quantity);
        payload.setTimestamp(System.currentTimeMillis());

        byte[] screenshot = null;
        if (config.includeScreenshot()) {
            screenshot = screenshotHelper.capture();
        }

        apiClient.submitProof(payload, screenshot).thenAccept(success -> {
            if (success && config.notifyOnSubmit()) {
                client.addChatMessage(
                    net.runelite.api.ChatMessageType.GAMEMESSAGE,
                    "",
                    "[Birdhouse] Proof submitted: " + match.getTileName() + " (" + itemName + ")",
                    ""
                );
            }
            if (success) {
                overlay.setLastMatch(match.getTileName(), itemName);
            }
        });

        log.info("Drop matched tile '{}': {} from {}", match.getTileName(), itemName, npcName);
    }
}
