package com.thebirdhouse.plugin;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

/**
 * Small overlay that shows tile progress and recent match info
 * in the top-left corner of the game screen.
 */
public class BirdhouseOverlay extends OverlayPanel {

    private final BirdhouseConfig config;
    private final DropMatcher dropMatcher;
    private final AchievementTracker achievementTracker;

    private String lastMatchInfo = null;
    private long lastMatchTime = 0;

    @Inject
    public BirdhouseOverlay(BirdhouseConfig config, DropMatcher dropMatcher, AchievementTracker achievementTracker) {
        this.config = config;
        this.dropMatcher = dropMatcher;
        this.achievementTracker = achievementTracker;

        setPosition(OverlayPosition.TOP_LEFT);
        setPriority(OverlayPriority.LOW);
    }

    public void setLastMatch(String tileName, String itemName) {
        this.lastMatchInfo = tileName + " (" + itemName + ")";
        this.lastMatchTime = System.currentTimeMillis();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay()) return null;

        BoardData board = dropMatcher.getActiveBoard();
        if (board == null || !config.autoSubmitDrops()) {
            return null;
        }

        int total = 0;
        int completed = 0;
        if (board.getTiles() != null) {
            total = board.getTiles().size();
            completed = (int) board.getTiles().stream().filter(BoardTile::isCompleted).count();
        }

        if (total == 0) return null;

        panelComponent.getChildren().add(TitleComponent.builder()
            .text("The Birdhouse")
            .color(new Color(93, 164, 196))
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Tiles:")
            .right(completed + " / " + total)
            .rightColor(completed == total ? Color.GREEN : Color.WHITE)
            .build());

        int remaining = total - completed;
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Remaining:")
            .right(String.valueOf(remaining))
            .rightColor(remaining == 0 ? Color.GREEN : new Color(255, 200, 100))
            .build());

        String roomCode = config.roomCode();
        if (roomCode != null && !roomCode.isEmpty()) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Room:")
                .right(roomCode)
                .rightColor(new Color(150, 150, 180))
                .build());
        }

        // Show last match for 30 seconds
        if (lastMatchInfo != null && System.currentTimeMillis() - lastMatchTime < 30000) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Last match:")
                .right("")
                .build());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("")
                .right(lastMatchInfo)
                .rightColor(Color.GREEN)
                .build());
        }

        return super.render(graphics);
    }
}
