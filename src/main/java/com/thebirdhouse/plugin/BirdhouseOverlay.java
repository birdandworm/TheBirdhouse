package com.thebirdhouse.plugin;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class BirdhouseOverlay extends OverlayPanel {

    private final BirdhouseConfig config;
    private final DropMatcher dropMatcher;

    private String lastMatchInfo = null;
    private long lastMatchTime = 0;

    @Inject
    public BirdhouseOverlay(BirdhouseConfig config, DropMatcher dropMatcher) {
        this.config = config;
        this.dropMatcher = dropMatcher;

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

        boolean isBattleship = "battleship".equals(board.getGameType());
        BoardData.BattleshipMeta bsMeta = board.getBattleshipMeta();

        if (isBattleship && bsMeta != null && bsMeta.getEnemyShipsTotal() != null && bsMeta.getEnemyShipsTotal() > 0) {
            // For battleship, track the opponent's fleet rather than board tiles.
            int shipTotal = bsMeta.getEnemyShipsTotal();
            int shipAfloat = bsMeta.getEnemyShipsRemaining() != null ? bsMeta.getEnemyShipsRemaining() : shipTotal;
            int shipSunk = shipTotal - shipAfloat;

            panelComponent.getChildren().add(LineComponent.builder()
                .left("Enemy sunk:")
                .right(shipSunk + " / " + shipTotal)
                .rightColor(shipSunk == shipTotal ? Color.GREEN : Color.WHITE)
                .build());

            panelComponent.getChildren().add(LineComponent.builder()
                .left("Afloat:")
                .right(String.valueOf(shipAfloat))
                .rightColor(shipAfloat == 0 ? Color.GREEN : new Color(255, 200, 100))
                .build());
        } else if (isBattleship) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Enemy fleet:")
                .right("hidden")
                .rightColor(new Color(150, 150, 180))
                .build());
        } else {
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
        }

        String roomCode = config.roomCode();
        if (roomCode != null && !roomCode.isEmpty()) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Room:")
                .right(roomCode)
                .rightColor(new Color(150, 150, 180))
                .build());
        }

        // Countdown
        Long deadline = board.getDeadline();
        if (deadline != null) {
            long timeLeft = deadline - System.currentTimeMillis();
            if (timeLeft > 0) {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Ends in:")
                    .right(formatCountdown(timeLeft))
                    .rightColor(timeLeft < 3600000 ? new Color(255, 100, 100) : new Color(255, 180, 80))
                    .build());
            } else {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status:")
                    .right("Ended")
                    .rightColor(new Color(255, 100, 100))
                    .build());
            }
        }

        // Current date/time for screenshot verification
        String now = new java.text.SimpleDateFormat("MM/dd/yy h:mm a").format(new java.util.Date());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Time:")
            .right(now)
            .rightColor(new Color(180, 180, 180))
            .build());

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

    private String formatCountdown(long millis) {
        long days = millis / (1000 * 60 * 60 * 24);
        long hours = (millis / (1000 * 60 * 60)) % 24;
        long mins = (millis / (1000 * 60)) % 60;
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, mins);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, mins);
        }
        return String.format("%dm", mins);
    }
}
