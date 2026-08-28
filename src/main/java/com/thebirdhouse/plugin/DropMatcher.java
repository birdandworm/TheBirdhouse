package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.plugins.loottracker.LootReceived;
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

    public void updateBoard(BoardData board) {
        if (board != null) {
            this.activeBoard = board;
            log.info("[Birdhouse] Board updated: {} tiles, gameType={}", board.getTiles() != null ? board.getTiles().size() : 0, board.getGameType());
        }
    }

    public void setActiveRoomCode(String roomCode) {
        this.activeRoomCode = roomCode;
    }

    public String getActiveRoomCode() {
        return activeRoomCode;
    }

    /**
     * Whether the tracked event has finished (deadline passed or host closed it).
     *
     * A room code lives in plugin config, so it outlives the event: once a bingo ends,
     * nothing clears it and every later kill would otherwise still be matched, uploaded
     * with a screenshot, and rejected by the server. That is pure waste which persists
     * for as long as the player leaves the plugin installed, so the submitting paths all
     * gate on this.
     *
     * Absent flag means live: an older server omits it, and going quiet against one
     * would break submission entirely rather than merely waste a request.
     */
    public boolean isEventOver() {
        BoardData board = activeBoard;
        return board != null && Boolean.FALSE.equals(board.getActive());
    }

    public void loadActiveBoard(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            log.warn("[Birdhouse] loadActiveBoard called with empty roomCode");
            return;
        }
        this.activeRoomCode = roomCode;
        log.info("[Birdhouse] Loading board for room: {}", roomCode);
        apiClient.fetchBoard(roomCode).thenAccept(board -> {
            if (board != null) {
                this.activeBoard = board;
                log.info("[Birdhouse] Board loaded for room {}: {} tiles, gameType={}", roomCode, board.getTiles().size(), board.getGameType());
                if (board.getTiles().size() > 0) {
                    BoardTile first = board.getTiles().get(0);
                    log.info("[Birdhouse]   First tile: name='{}', matchName='{}', matchItems={}", first.getName(), first.getMatchName(), first.getMatchItems());
                }
            } else {
                log.warn("[Birdhouse] Board fetch returned null for room {}", roomCode);
            }
        });
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        if (!config.autoSubmitDrops()) {
            log.info("[Birdhouse] Auto-submit disabled in config, skipping loot event");
            return;
        }
        if (activeBoard == null) {
            log.info("[Birdhouse] No active board loaded, skipping loot event");
            return;
        }
        if (isEventOver()) {
            log.debug("[Birdhouse] Event {} has ended, not matching drops", activeRoomCode);
            return;
        }

        String sourceName = event.getName();

        log.info("[Birdhouse] Loot received from '{}' (type={}): {} items", sourceName, event.getType(), event.getItems().size());

        for (ItemStack itemStack : event.getItems()) {
            ItemComposition itemComp = itemManager.getItemComposition(itemStack.getId());
            String itemName = itemComp.getName();
            int quantity = itemStack.getQuantity();

            log.info("[Birdhouse]   Item: '{}' x{}", itemName, quantity);

            List<TileMatch> matches = findMatches(sourceName, itemName, quantity);
            if (matches.isEmpty()) {
                log.info("[Birdhouse]   No tile match for '{}'", itemName);
            } else {
                TileMatch match = matches.get(0);
                log.info("[Birdhouse]   MATCH! tile='{}' key={} (of {} matching tiles)", match.getTileName(), match.getTileKey(), matches.size());
                // Drops don't count until the board is locked / event has started.
                // Warn the player to screenshot so the drop isn't lost (older servers omit
                // the flag → treat as started and submit as before).
                if (Boolean.FALSE.equals(activeBoard.getStarted())) {
                    log.info("[Birdhouse]   Event not started — not submitting '{}', warning player to screenshot", itemName);
                    warnNotStarted(match, itemName);
                } else {
                    submitMatch(match, sourceName, itemName, quantity, itemStack.getId());
                }
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

            // Skip tiles that already have enough submissions (even if not yet marked complete)
            if (tile.getQuantity() > 1 && tile.getCurrentQty() >= tile.getQuantity()) continue;

            // For battleship, skip tiles that already have an attack result (hit, miss, or sunk)
            if ("battleship".equals(tile.getGameType()) && tile.getAttackResult() != null) continue;

            // For tile race, only match the tile the player is currently on
            if ("tilerace".equals(tile.getGameType()) && !tile.isCurrent()) continue;

            // For chip drop, only match available (unlocked) tiles
            if ("chipdrop".equals(tile.getGameType()) && !tile.isAvailable()) continue;

            if (matchesTile(tile, npcName, itemName)) {
                matches.add(new TileMatch(tile.getKey(), tile.getName(), tile.getGameType(), tile.getTerritoryName()));
            }
        }
        return matches;
    }

    private boolean matchesTile(BoardTile tile, String npcName, String itemName) {
        // Check matchItems list first (generic tiles / presets / collect-all)
        List<String> matchItems = tile.getMatchItems();
        if (matchItems != null && !matchItems.isEmpty()) {
            String item = itemName.toLowerCase();
            for (String acceptable : matchItems) {
                if (acceptable != null && acceptable.toLowerCase().equals(item)) {
                    // For "collect all" tiles, only stop once every slot that accepts this
                    // item is already full (so x4 / OR-alternative slots keep matching).
                    if (tile.isMatchAll() && itemAlreadySatisfied(tile, item)) {
                        log.debug("[Birdhouse]     '{}' no longer needed for collect-all tile '{}'", itemName, tile.getName());
                        return false;
                    }
                    log.debug("[Birdhouse]     matchItems hit: '{}' in list for tile '{}'", itemName, tile.getName());
                    return true;
                }
            }
            return false;
        }

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

    // For a collect-all tile: is this item no longer needed? True only when every slot
    // that accepts the item has already reached its required quantity.
    private boolean itemAlreadySatisfied(BoardTile tile, String itemLower) {
        java.util.Map<String, Integer> counts = tile.getCollectedCounts();

        // Preferred path: slot (OR-group) definitions.
        if (tile.getMatchGroups() != null && !tile.getMatchGroups().isEmpty()) {
            for (BoardTile.MatchGroup g : tile.getMatchGroups()) {
                if (g.getItems() == null) continue;
                boolean contains = false;
                for (String it : g.getItems()) {
                    if (it != null && it.toLowerCase().equals(itemLower)) { contains = true; break; }
                }
                if (!contains) continue;
                int need = g.getQty() > 0 ? g.getQty() : 1;
                int have = 0;
                if (counts != null) {
                    for (String it : g.getItems()) {
                        Integer c = it == null ? null : counts.get(it.toLowerCase());
                        if (c != null) have += c;
                    }
                }
                if (have < need) return false; // this slot still needs the item
            }
            return true;
        }

        // Fallback: per-item quantities (flat collect-all tiles / older servers).
        int need = 1;
        if (tile.getItemQuantities() != null) {
            Integer q = tile.getItemQuantities().get(itemLower);
            if (q != null && q > 0) need = q;
        }
        int have = 0;
        if (counts != null) {
            Integer c = counts.get(itemLower);
            if (c != null) have = c;
        }
        if (counts == null && tile.getCollectedItems() != null) {
            for (String c : tile.getCollectedItems()) {
                if (c != null && c.toLowerCase().equals(itemLower)) { have = Math.max(have, 1); break; }
            }
        }
        return have >= need;
    }

    private void warnNotStarted(TileMatch match, String itemName) {
        client.addChatMessage(
            net.runelite.api.ChatMessageType.GAMEMESSAGE,
            "",
            "[Birdhouse] Event not started yet \u2014 screenshot this drop (" + itemName
                + ") so it can be submitted after the board is locked!",
            ""
        );
        overlay.setLastMatch(match.getTileName() + " (not started \u2014 screenshot!)", itemName);
    }

    private void submitMatch(TileMatch match, String npcName, String itemName, int quantity, int itemId) {
        String playerName = client.getLocalPlayer().getName();

        ProofPayload payload = new ProofPayload();
        payload.setRoomCode(activeRoomCode);
        payload.setPlayerName(playerName);
        payload.setTileKey(match.getTileKey());
        payload.setTileName(match.getTileName());
        payload.setTerritoryName(match.getTerritoryName());
        payload.setGameType(match.getGameType());
        payload.setItemName(itemName);
        payload.setNpcName(npcName);
        payload.setQuantity(quantity);
        payload.setItemId(itemId);
        payload.setValue(stackValue(itemId, quantity));
        payload.setTimestamp(System.currentTimeMillis());

        if (config.includeScreenshot()) {
            screenshotHelper.captureAsync(screenshot -> {
                if (screenshot == null || screenshot.length == 0) {
                    log.warn("[Birdhouse] Screenshot capture returned null/empty for '{}' - submitting without image", match.getTileName());
                }
                doSubmit(payload, screenshot, match, itemName);
            });
        } else {
            log.info("[Birdhouse] Screenshots disabled in config, submitting without image");
            doSubmit(payload, null, match, itemName);
        }

        log.info("Drop matched tile '{}': {} from {}", match.getTileName(), itemName, npcName);
    }

    /** Grand Exchange value of a whole stack; 0 for untradeables and unknown ids. */
    private long stackValue(int itemId, int quantity) {
        try {
            int price = itemManager.getItemPrice(itemId);
            return (long) Math.max(0, price) * Math.max(0, quantity);
        } catch (Exception e) {
            return 0;
        }
    }

    private void doSubmit(ProofPayload payload, byte[] screenshot, TileMatch match, String itemName) {
        log.info("[Birdhouse] Submitting proof: tile='{}', item='{}', screenshot={} bytes", match.getTileName(), itemName, screenshot != null ? screenshot.length : 0);
        apiClient.submitProof(payload, screenshot).thenAccept(result -> {
            if (result.isOk()) {
                log.info("[Birdhouse] Proof submitted successfully for '{}'", match.getTileName());
                if (config.notifyOnSubmit() && !result.isDuplicate()) {
                    client.addChatMessage(
                        net.runelite.api.ChatMessageType.GAMEMESSAGE,
                        "",
                        "[Birdhouse] Proof submitted: " + match.getTileName() + " (" + itemName + ")",
                        ""
                    );
                }
                overlay.setLastMatch(match.getTileName(), itemName);
            } else {
                log.warn("[Birdhouse] Proof submission FAILED for '{}': {}", match.getTileName(), result.getMessage());
            }
        });
    }
}
