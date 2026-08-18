package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects collection log additions, quest completions, level-ups,
 * and other achievements that might match board tiles.
 */
@Slf4j
@Singleton
public class AchievementTracker {

    private static final Pattern COLLECTION_LOG_PATTERN =
        Pattern.compile("New item added to your collection log: (.+)");
    private static final Pattern QUEST_COMPLETE_PATTERN =
        Pattern.compile("Congratulations, you've completed a quest: (.+)");
    private static final Pattern LEVEL_UP_PATTERN =
        Pattern.compile("Congratulations, you've reached (\\w+) level (\\d+)");
    private static final Pattern PET_PATTERN =
        Pattern.compile("You have a funny feeling like you're being followed");

    @Inject
    private Client client;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private ScreenshotHelper screenshotHelper;

    private String activeRoomCode;
    private BoardData activeBoard;

    public void setActiveBoard(String roomCode, BoardData board) {
        this.activeRoomCode = roomCode;
        this.activeBoard = board;
    }

    /**
     * Whether there is a live event to submit against. Collection-log entries, quests and
     * pets keep arriving long after a bingo ends, and the configured room code outlives
     * the event, so without this every one of them would be uploaded and rejected for as
     * long as the plugin stays installed. Absent flags mean live, to stay compatible with
     * a server that predates them.
     */
    private boolean eventLive() {
        BoardData board = activeBoard;
        if (board == null) return false;
        return !Boolean.FALSE.equals(board.getActive())
            && !Boolean.FALSE.equals(board.getStarted());
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!config.autoSubmitDrops() || !eventLive()) return;

        String message = event.getMessage();
        if (message == null) return;

        // Strip colour/formatting tags
        String clean = message.replaceAll("<[^>]+>", "").trim();

        // Collection log
        Matcher collLogMatcher = COLLECTION_LOG_PATTERN.matcher(clean);
        if (collLogMatcher.find()) {
            String itemName = collLogMatcher.group(1);
            tryMatchAndSubmit(itemName, "collection_log");
            return;
        }

        // Quest completion
        Matcher questMatcher = QUEST_COMPLETE_PATTERN.matcher(clean);
        if (questMatcher.find()) {
            String questName = questMatcher.group(1);
            tryMatchAndSubmit(questName, "quest");
            return;
        }

        // Pet drop
        if (PET_PATTERN.matcher(clean).find()) {
            tryMatchAndSubmit("Pet drop", "pet");
            return;
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        if (!config.autoSubmitDrops() || !eventLive()) return;

        int level = event.getLevel();
        String skillName = event.getSkill().getName();

        // Check for milestone levels that might match tiles
        // Common milestones: 99, base levels, specific level thresholds
        String milestone = skillName + " level " + level;
        String milestone99 = level == 99 ? "99 " + skillName : null;

        tryMatchAndSubmit(milestone, "level_up");
        if (milestone99 != null) {
            tryMatchAndSubmit(milestone99, "level_up");
            tryMatchAndSubmit("99 in any skill", "level_up");
        }
    }

    private void tryMatchAndSubmit(String achievementName, String achievementType) {
        if (activeBoard == null || activeBoard.getTiles() == null) return;

        String search = achievementName.toLowerCase();
        // Collection-log unlocks are concrete items: match them strictly against the
        // tile's accepted item list (or an exact matchName). Loose substring matching
        // caused false positives (e.g. "Vorkath's head" matching a "Vorkath" visage
        // tile). Quests/pets/levels stay on the looser label match.
        boolean strict = "collection_log".equals(achievementType);

        for (BoardTile tile : activeBoard.getTiles()) {
            if (tile.isCompleted()) continue;

            if (strict) {
                if (collectionItemMatchesTile(tile, search)) {
                    submitAchievement(tile, achievementName, achievementType);
                    break;
                }
                continue;
            }

            String matchName = tile.getMatchName();
            if (matchName == null || matchName.isEmpty()) continue;

            if (matchName.toLowerCase().equals(search) ||
                matchName.toLowerCase().contains(search) ||
                search.contains(matchName.toLowerCase())) {

                submitAchievement(tile, achievementName, achievementType);
                break;
            }
        }
    }

    // Strict collection-log matching: the unlocked item must be one of the tile's
    // explicit accepted items (matchItems), or exactly equal the tile's matchName.
    // No substring matching — that produced cross-tile false positives.
    private boolean collectionItemMatchesTile(BoardTile tile, String itemLower) {
        List<String> matchItems = tile.getMatchItems();
        if (matchItems != null && !matchItems.isEmpty()) {
            for (String acc : matchItems) {
                if (acc != null && acc.toLowerCase().equals(itemLower)) return true;
            }
            return false;
        }
        String matchName = tile.getMatchName();
        if (matchName == null || matchName.isEmpty()) return false;
        return matchName.toLowerCase().equals(itemLower);
    }

    private void submitAchievement(BoardTile tile, String achievementName, String achievementType) {
        String playerName = client.getLocalPlayer().getName();

        ProofPayload payload = new ProofPayload();
        payload.setRoomCode(activeRoomCode);
        payload.setPlayerName(playerName);
        payload.setTileKey(tile.getKey());
        payload.setTileName(tile.getName());
        payload.setGameType(tile.getGameType());
        payload.setItemName(achievementName);
        payload.setNpcName(achievementType);
        payload.setQuantity(1);
        payload.setTimestamp(System.currentTimeMillis());

        if (config.includeScreenshot()) {
            screenshotHelper.captureAsync(screenshot -> {
                apiClient.submitProof(payload, screenshot).thenAccept(success -> {
                    if (success && config.notifyOnSubmit()) {
                        client.addChatMessage(
                            ChatMessageType.GAMEMESSAGE,
                            "",
                            "[Birdhouse] Achievement matched: " + tile.getName() + " (" + achievementName + ")",
                            ""
                        );
                    }
                });
            });
        } else {
            apiClient.submitProof(payload, null).thenAccept(success -> {
                if (success && config.notifyOnSubmit()) {
                    client.addChatMessage(
                        ChatMessageType.GAMEMESSAGE,
                        "",
                        "[Birdhouse] Achievement matched: " + tile.getName() + " (" + achievementName + ")",
                        ""
                    );
                }
            });
        }

        log.info("Achievement matched tile '{}': {} ({})", tile.getName(), achievementName, achievementType);
    }
}
