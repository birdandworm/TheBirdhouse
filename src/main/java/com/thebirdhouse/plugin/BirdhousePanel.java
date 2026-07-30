package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class BirdhousePanel extends PluginPanel {

    private static final Color COLOR_COMPLETED = new Color(100, 200, 100);
    private static final Color COLOR_CURRENT = new Color(255, 200, 100);
    private static final Color COLOR_AVAILABLE = new Color(255, 200, 100);
    private static final Color COLOR_LOCKED = new Color(60, 60, 70);
    private static final Color COLOR_INCOMPLETE = new Color(120, 120, 140);
    private static final Color COLOR_FREE = new Color(93, 164, 196);
    private static final Color COLOR_BRAND = new Color(93, 164, 196);

    private static final int PANEL_WIDTH = 225;
    // Poll quickly while a game is live; back off hard when idle or the game has ended
    // so we don't keep hitting the server for dead/stale rooms.
    private static final int POLL_ACTIVE_SECONDS = 60;
    private static final int POLL_IDLE_SECONDS = 300;

    private final BirdhouseConfig config;
    private final DropMatcher dropMatcher;
    private final BirdhouseApiClient apiClient;

    private final JPanel mainPanel;
    private final JPanel boardPanel;
    private final JPanel tilesPanel;
    private final JLabel statusLabel;
    private final JLabel progressLabel;
    private final JLabel countdownLabel;
    private final JButton refreshButton;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> autoRefreshTask;
    private ScheduledFuture<?> countdownTask;
    private Long currentDeadline;

    @Inject
    public BirdhousePanel(BirdhouseConfig config, DropMatcher dropMatcher, BirdhouseApiClient apiClient) {
        super(false);
        this.config = config;
        this.dropMatcher = dropMatcher;
        this.apiClient = apiClient;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel titleLabel = new JLabel("The Birdhouse");
        titleLabel.setForeground(COLOR_BRAND);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        refreshButton = new JButton("\u21BB");
        refreshButton.setToolTipText("Refresh board");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> refreshBoard());
        headerPanel.add(refreshButton, BorderLayout.EAST);

        // Status
        JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));
        statusPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        statusPanel.setBorder(new EmptyBorder(8, 0, 8, 0));

        statusLabel = new JLabel("Not connected");
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel.add(statusLabel);

        progressLabel = new JLabel("");
        progressLabel.setForeground(new Color(150, 200, 100));
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.BOLD, 13f));
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel.add(progressLabel);

        countdownLabel = new JLabel("");
        countdownLabel.setForeground(new Color(255, 180, 80));
        countdownLabel.setFont(countdownLabel.getFont().deriveFont(11f));
        countdownLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel.add(countdownLabel);

        // Mini board area
        boardPanel = new JPanel();
        boardPanel.setLayout(new BorderLayout());
        boardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        boardPanel.setBorder(new EmptyBorder(0, 0, 8, 0));

        // Tiles list
        tilesPanel = new JPanel();
        tilesPanel.setLayout(new BoxLayout(tilesPanel, BoxLayout.Y_AXIS));
        tilesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scrollPane = new JScrollPane(tilesPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // Main content panel with proper layout
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainPanel.add(boardPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Top section
        JPanel topSection = new JPanel();
        topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
        topSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topSection.add(headerPanel);
        topSection.add(statusPanel);

        add(topSection, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);
    }

    public void startAutoRefresh() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        stopAutoRefresh();
        scheduleNextRefresh(POLL_ACTIVE_SECONDS);
    }

    // Self-rescheduling poll: each refresh decides how long to wait before the next one
    // based on whether the game is still live.
    private void scheduleNextRefresh(int seconds) {
        if (scheduler == null || scheduler.isShutdown()) return;
        if (autoRefreshTask != null) {
            autoRefreshTask.cancel(false);
        }
        autoRefreshTask = scheduler.schedule(
            () -> SwingUtilities.invokeLater(this::refreshBoard),
            seconds, TimeUnit.SECONDS
        );
    }

    public void stopAutoRefresh() {
        if (autoRefreshTask != null) {
            autoRefreshTask.cancel(false);
            autoRefreshTask = null;
        }
        if (countdownTask != null) {
            countdownTask.cancel(false);
            countdownTask = null;
        }
    }

    public void shutdown() {
        stopAutoRefresh();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private String activeRoomCode;

    public void setActiveRoomCode(String code) {
        this.activeRoomCode = code;
    }

    public void refreshBoard() {
        String roomCode = config.roomCode();
        if (roomCode != null) {
            roomCode = roomCode.trim();
        }

        // Fall back to auto-detected room if no manual code
        if ((roomCode == null || roomCode.isEmpty()) && activeRoomCode != null && !activeRoomCode.isEmpty()) {
            roomCode = activeRoomCode;
        }

        if (roomCode == null || roomCode.isEmpty()) {
            String token = config.authToken();
            if (token == null || token.trim().isEmpty()) {
                statusLabel.setText("\u26A0 No auth token configured");
                statusLabel.setForeground(new Color(255, 120, 120));
            } else {
                statusLabel.setText("\u26A0 No active room found");
                statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            }
            progressLabel.setText("");
            countdownLabel.setText("");
            boardPanel.removeAll();
            tilesPanel.removeAll();
            boardPanel.revalidate();
            tilesPanel.revalidate();
            // No room to track — idle until login/config change or a manual refresh.
            scheduleNextRefresh(POLL_IDLE_SECONDS);
            return;
        }

        final String fetchCode = roomCode;
        statusLabel.setText("Loading...");
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        refreshButton.setEnabled(false);

        apiClient.fetchBoard(fetchCode).thenAccept(board -> {
            SwingUtilities.invokeLater(() -> {
                refreshButton.setEnabled(true);
                if (board == null) {
                    statusLabel.setText("Failed to load board");
                    progressLabel.setText("");
                    countdownLabel.setText("");
                    // Transient failure — retry on the normal cadence.
                    scheduleNextRefresh(POLL_ACTIVE_SECONDS);
                    return;
                }
                dropMatcher.setActiveRoomCode(fetchCode);
                dropMatcher.updateBoard(board);
                updatePanel(board, fetchCode);
                // Back off once the game is no longer live (older servers omit the flag → treat as live).
                boolean live = board.getActive() == null || board.getActive();
                scheduleNextRefresh(live ? POLL_ACTIVE_SECONDS : POLL_IDLE_SECONDS);
            });
        });
    }

    private void updatePanel(BoardData board, String roomCode) {
        List<BoardTile> tiles = board.getTiles();
        if (tiles == null || tiles.isEmpty()) {
            statusLabel.setText("Room: " + roomCode + " (no tiles)");
            progressLabel.setText("");
            countdownLabel.setText("");
            boardPanel.removeAll();
            tilesPanel.removeAll();
            boardPanel.revalidate();
            tilesPanel.revalidate();
            return;
        }

        String gameType = board.getGameType();
        int total = tiles.size();
        int completed = (int) tiles.stream().filter(BoardTile::isCompleted).count();
        int remaining = total - completed;

        boolean ended = Boolean.FALSE.equals(board.getActive());
        statusLabel.setText("Room: " + roomCode + " \u2022 " + formatGameType(gameType) + (ended ? " (ended)" : ""));

        // For battleship, the meaningful progress is how many of the opponent's ships are
        // still afloat \u2014 not how many board tiles are unmarked.
        // The Delve has no checklist to complete — supplies are the currency and sigils are
        // the objective, so a tile count says nothing about how the run is going.
        if ("delve".equals(gameType)) {
            BoardData.DelveMeta dm = board.getDelveMeta();
            if (dm != null) {
                String sigils = dm.getSigilsNeeded() > 0
                    ? "  \u2022  " + dm.getSigilsHeld() + "/" + dm.getSigilsNeeded() + " sigils"
                    : "";
                progressLabel.setText(dm.getSupplies() + " supplies" + sigils);
            } else {
                progressLabel.setText("Waiting for the Delve to be set up");
            }
        } else if ("battleship".equals(gameType)) {
            BoardData.BattleshipMeta meta = board.getBattleshipMeta();
            if (meta != null && meta.getEnemyShipsTotal() != null && meta.getEnemyShipsTotal() > 0) {
                int shipTotal = meta.getEnemyShipsTotal();
                int shipRemaining = meta.getEnemyShipsRemaining() != null ? meta.getEnemyShipsRemaining() : shipTotal;
                int shipSunk = shipTotal - shipRemaining;
                progressLabel.setText(shipSunk + " / " + shipTotal + " enemy ships sunk (" + shipRemaining + " afloat)");
            } else {
                progressLabel.setText("Enemy fleet hidden until ships are placed");
            }
        } else {
            progressLabel.setText(completed + " / " + total + " complete (" + remaining + " remaining)");
        }

        // Countdown
        currentDeadline = board.getDeadline();
        updateCountdown();
        startCountdownTimer();

        // Render mini board
        boardPanel.removeAll();
        boardPanel.add(renderMiniBoard(board), BorderLayout.CENTER);
        boardPanel.revalidate();
        boardPanel.repaint();

        // Render tile list
        tilesPanel.removeAll();
        renderTileList(board);
        tilesPanel.revalidate();
        tilesPanel.repaint();
    }

    private void startCountdownTimer() {
        if (countdownTask != null) {
            countdownTask.cancel(false);
        }
        if (currentDeadline == null) return;
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        countdownTask = scheduler.scheduleAtFixedRate(
            () -> SwingUtilities.invokeLater(this::updateCountdown),
            1, 1, TimeUnit.SECONDS
        );
    }

    private void updateCountdown() {
        if (currentDeadline == null) {
            countdownLabel.setText("");
            return;
        }
        long remaining = currentDeadline - System.currentTimeMillis();
        if (remaining <= 0) {
            countdownLabel.setText("\u23F0 Event ended!");
            countdownLabel.setForeground(new Color(255, 100, 100));
            return;
        }
        long days = remaining / (1000 * 60 * 60 * 24);
        long hours = (remaining / (1000 * 60 * 60)) % 24;
        long mins = (remaining / (1000 * 60)) % 60;
        long secs = (remaining / 1000) % 60;

        String timeStr;
        if (days > 0) {
            timeStr = String.format("%dd %dh %dm %ds", days, hours, mins, secs);
        } else if (hours > 0) {
            timeStr = String.format("%dh %dm %ds", hours, mins, secs);
        } else {
            timeStr = String.format("%dm %ds", mins, secs);
        }
        countdownLabel.setText("\u23F0 " + timeStr + " remaining");
        countdownLabel.setForeground(remaining < 3600000 ? new Color(255, 100, 100) : new Color(255, 180, 80));
    }

    // ===== MINI BOARD RENDERERS =====

    private JPanel renderMiniBoard(BoardData board) {
        String gameType = board.getGameType();
        switch (gameType != null ? gameType : "") {
            case "bingo":
            case "battleship":
                return renderGridBoard(board);
            case "chipdrop":
                return renderChipDropBoard(board);
            case "tilerace":
                return renderTileRaceBoard(board);
            case "territory":
                return renderTerritoryBoard(board);
            case "monopoly":
                return renderMonopolyBoard(board);
            case "delve":
                return renderDelveBoard(board);
            default:
                return new JPanel();
        }
    }

    // A 16x16 Delve map is unreadable at panel width and the website already draws it, so
    // this space goes to the numbers a player actually acts on: what the party can spend,
    // and where the supplies came from.
    private JPanel renderDelveBoard(BoardData board) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        BoardData.DelveMeta dm = board.getDelveMeta();
        if (dm == null) return panel;

        JLabel supplies = new JLabel(String.valueOf(dm.getSupplies()));
        supplies.setForeground(COLOR_BRAND);
        supplies.setFont(supplies.getFont().deriveFont(Font.BOLD, 26f));
        supplies.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(supplies);

        JLabel caption = new JLabel("supplies to spend");
        caption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        caption.setFont(caption.getFont().deriveFont(10f));
        caption.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(caption);

        panel.add(Box.createVerticalStrut(4));

        JLabel split = new JLabel("\u2694 " + dm.getFromKills()
            + "   \uD83D\uDCE6 " + dm.getBonusEarned()
            + "   \u2212" + dm.getSpent() + " spent");
        split.setForeground(new Color(150, 150, 180));
        split.setFont(split.getFont().deriveFont(10f));
        split.setAlignmentX(Component.CENTER_ALIGNMENT);
        split.setToolTipText("Earned from kills, plus bonus drops, minus what the party has spent");
        panel.add(split);

        String progress = dm.getRoomsOpened() + " rooms opened";
        if (dm.isVaultCleared()) progress = "\uD83C\uDFC6 Vault cracked \u2022 " + progress;
        JLabel rooms = new JLabel(progress);
        rooms.setForeground(dm.isVaultCleared() ? COLOR_COMPLETED : new Color(150, 150, 180));
        rooms.setFont(rooms.getFont().deriveFont(10f));
        rooms.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(rooms);

        return panel;
    }

    private static final Color COLOR_OPPONENT = new Color(220, 80, 80);
    private static final Color COLOR_HIT = new Color(220, 60, 60);
    private static final Color COLOR_SUNK = new Color(160, 40, 40);
    private static final Color COLOR_MISS = new Color(80, 80, 120);
    private static final Color COLOR_ATTACKABLE = new Color(220, 140, 60);

    private int calculateCellSize(int cols, int rows) {
        int availableWidth = PANEL_WIDTH - 28; // padding + border
        int cellWithGap = availableWidth / cols;
        int size = Math.max(8, cellWithGap - 2); // subtract gap, min 8px
        return Math.min(size, 18); // max 18px
    }

    private JPanel renderGridBoard(BoardData board) {
        int rows = board.getRows();
        int cols = board.getCols();
        if (rows == 0 || cols == 0) return new JPanel();

        // For battleship, show two grids (attack + defense)
        if ("battleship".equals(board.getGameType())) {
            return renderBattleshipGrids(board);
        }

        int cellSize = calculateCellSize(cols, rows);

        JPanel grid = new JPanel(new GridLayout(rows, cols, 1, 1));
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<BoardTile> tiles = board.getTiles();
        int tileIdx = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                JPanel cell = new JPanel();
                cell.setPreferredSize(new Dimension(cellSize, cellSize));

                if (tileIdx < tiles.size()) {
                    BoardTile tile = tiles.get(tileIdx);
                    String expectedKey = r + "-" + c;
                    if (expectedKey.equals(tile.getKey())) {
                        if (tile.isFree()) {
                            cell.setBackground(COLOR_FREE);
                        } else if (tile.isCompleted()) {
                            cell.setBackground(COLOR_COMPLETED);
                        } else {
                            cell.setBackground(COLOR_INCOMPLETE);
                        }
                        cell.setToolTipText(tile.getName());
                        tileIdx++;
                    } else {
                        cell.setBackground(COLOR_LOCKED);
                    }
                } else {
                    cell.setBackground(COLOR_LOCKED);
                }

                grid.add(cell);
            }
        }

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(grid);
        return wrapper;
    }

    private JPanel renderBattleshipGrids(BoardData board) {
        int rows = board.getRows();
        int cols = board.getCols();
        int cellSize = calculateCellSize(cols, rows);

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Attack grid (our shots on their board)
        JLabel attackLabel = new JLabel("Your Attacks:");
        attackLabel.setForeground(COLOR_BRAND);
        attackLabel.setFont(attackLabel.getFont().deriveFont(Font.BOLD, 10f));
        attackLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        container.add(attackLabel);
        container.add(Box.createVerticalStrut(2));

        JPanel attackGrid = new JPanel(new GridLayout(rows, cols, 1, 1));
        attackGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        List<BoardTile> tiles = board.getTiles();
        int tileIdx = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                JPanel cell = new JPanel();
                cell.setPreferredSize(new Dimension(cellSize, cellSize));
                if (tileIdx < tiles.size()) {
                    BoardTile tile = tiles.get(tileIdx);
                    String result = tile.getAttackResult();
                    if ("hit".equals(result) || "sunk".equals(result)) {
                        cell.setBackground("sunk".equals(result) ? COLOR_SUNK : COLOR_HIT);
                        cell.setToolTipText("sunk".equals(result) ? "\u2620 SUNK: " + tile.getName() : "\u2716 HIT: " + tile.getName());
                    } else if ("miss".equals(result)) {
                        cell.setBackground(COLOR_MISS);
                        cell.setToolTipText("\u25CB Miss");
                    } else {
                        cell.setBackground(COLOR_INCOMPLETE);
                        cell.setToolTipText(tile.getName());
                    }
                    tileIdx++;
                } else {
                    cell.setBackground(COLOR_LOCKED);
                }
                attackGrid.add(cell);
            }
        }
        JPanel attackWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        attackWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        attackWrapper.add(attackGrid);
        container.add(attackWrapper);

        // Defense grid (their shots on our board)
        BoardData.BattleshipMeta meta = board.getBattleshipMeta();
        if (meta != null && meta.getDefenseGrid() != null) {
            container.add(Box.createVerticalStrut(6));
            JLabel defLabel = new JLabel("Your Fleet:");
            defLabel.setForeground(COLOR_BRAND);
            defLabel.setFont(defLabel.getFont().deriveFont(Font.BOLD, 10f));
            defLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            container.add(defLabel);
            container.add(Box.createVerticalStrut(2));

            java.util.Map<String, String> defGrid = meta.getDefenseGrid();
            JPanel defenseGrid = new JPanel(new GridLayout(rows, cols, 1, 1));
            defenseGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    JPanel cell = new JPanel();
                    cell.setPreferredSize(new Dimension(cellSize, cellSize));
                    String key = r + "-" + c;
                    String defResult = defGrid.get(key);
                    if ("hit".equals(defResult) || "sunk".equals(defResult)) {
                        cell.setBackground("sunk".equals(defResult) ? COLOR_SUNK : COLOR_HIT);
                        cell.setToolTipText("sunk".equals(defResult) ? "\u2620 Enemy sunk your ship!" : "Enemy hit here!");
                    } else if ("miss".equals(defResult)) {
                        cell.setBackground(COLOR_MISS);
                        cell.setToolTipText("Enemy missed");
                    } else {
                        cell.setBackground(new Color(50, 70, 90));
                        cell.setToolTipText("Safe");
                    }
                    defenseGrid.add(cell);
                }
            }
            JPanel defWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            defWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
            defWrapper.add(defenseGrid);
            container.add(defWrapper);
        }

        return container;
    }

    private JPanel renderChipDropBoard(BoardData board) {
        int rows = board.getRows();
        int cols = board.getCols();
        if (rows == 0 || cols == 0) return new JPanel();

        int cellSize = calculateCellSize(cols, rows);

        JPanel grid = new JPanel(new GridLayout(rows, cols, 1, 1));
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<BoardTile> tiles = board.getTiles();
        int tileIdx = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                JPanel cell = new JPanel();
                cell.setPreferredSize(new Dimension(cellSize, cellSize));

                if (tileIdx < tiles.size()) {
                    BoardTile tile = tiles.get(tileIdx);
                    String expectedKey = r + "-" + c;
                    if (expectedKey.equals(tile.getKey())) {
                        if (tile.isCompleted()) {
                            cell.setBackground(COLOR_COMPLETED);
                        } else if (tile.isAvailable()) {
                            cell.setBackground(COLOR_AVAILABLE);
                        } else {
                            cell.setBackground(COLOR_LOCKED);
                        }
                        cell.setToolTipText(tile.getName() + (tile.isAvailable() ? " (available)" : tile.isCompleted() ? " (claimed)" : " (locked)"));
                        tileIdx++;
                    } else {
                        cell.setBackground(COLOR_LOCKED);
                    }
                } else {
                    cell.setBackground(COLOR_LOCKED);
                }

                grid.add(cell);
            }
        }

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(grid);
        return wrapper;
    }

    private JPanel renderTileRaceBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();
        int tilesPerRow = Math.min(12, tiles.size());
        int totalRows = (int) Math.ceil(tiles.size() / (double) tilesPerRow);
        int cellSize = calculateCellSize(tilesPerRow, totalRows);

        // Gather opponent positions
        java.util.Set<Integer> opponentPositions = new java.util.HashSet<>();
        if (board.getOpponents() != null) {
            for (BoardData.OpponentPosition opp : board.getOpponents()) {
                opponentPositions.add(opp.getPosition());
            }
        }

        JPanel track = new JPanel(new GridLayout(totalRows, tilesPerRow, 1, 1));
        track.setBackground(ColorScheme.DARK_GRAY_COLOR);
        track.setBorder(new EmptyBorder(4, 4, 4, 4));

        for (int i = 0; i < totalRows * tilesPerRow; i++) {
            JPanel cell = new JPanel();
            cell.setPreferredSize(new Dimension(cellSize, cellSize));

            if (i < tiles.size()) {
                BoardTile tile = tiles.get(i);
                String special = tile.getSpecial();

                if ("start".equals(special)) {
                    cell.setBackground(COLOR_FREE);
                    cell.setToolTipText("Start");
                } else if ("finish".equals(special)) {
                    cell.setBackground(new Color(200, 100, 200));
                    cell.setToolTipText("Finish");
                } else if ("forward".equals(special) || "backward".equals(special)) {
                    cell.setBackground(new Color(180, 140, 80));
                    cell.setToolTipText(tile.getName());
                } else if (tile.isCurrent()) {
                    cell.setBackground(COLOR_CURRENT);
                    cell.setToolTipText("\u25B6 " + tile.getName() + " (YOU)");
                } else if (opponentPositions.contains(i)) {
                    cell.setBackground(COLOR_OPPONENT);
                    String oppNames = getOpponentNamesAt(board, i);
                    cell.setToolTipText("\u2716 " + tile.getName() + " (" + oppNames + ")");
                } else if (tile.isCompleted()) {
                    cell.setBackground(COLOR_COMPLETED);
                    cell.setToolTipText("\u2713 " + tile.getName());
                } else {
                    cell.setBackground(COLOR_INCOMPLETE);
                    cell.setToolTipText(tile.getName());
                }
            } else {
                cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }

            track.add(cell);
        }

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(track);
        return wrapper;
    }

    private String getOpponentNamesAt(BoardData board, int position) {
        if (board.getOpponents() == null) return "Opponent";
        return board.getOpponents().stream()
            .filter(o -> o.getPosition() == position)
            .map(BoardData.OpponentPosition::getName)
            .collect(Collectors.joining(", "));
    }

    private JPanel renderTerritoryBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        // Group tiles by status: ours, attackable, enemy
        List<BoardTile> ourTiles = tiles.stream().filter(BoardTile::isOurs).collect(Collectors.toList());
        List<BoardTile> attackable = tiles.stream().filter(t -> t.isAttackable() && !t.isOurs()).collect(Collectors.toList());
        List<BoardTile> enemyLocked = tiles.stream().filter(t -> !t.isOurs() && !t.isAttackable()).collect(Collectors.toList());

        // Our territories
        if (!ourTiles.isEmpty()) {
            JPanel section = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            section.setBackground(ColorScheme.DARK_GRAY_COLOR);
            section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            JLabel label = new JLabel("Yours (" + ourTiles.size() + "):");
            label.setForeground(COLOR_COMPLETED);
            label.setFont(label.getFont().deriveFont(10f));
            section.add(label);
            for (BoardTile tile : ourTiles) {
                JPanel dot = new JPanel();
                dot.setPreferredSize(new Dimension(10, 10));
                dot.setBackground(COLOR_COMPLETED);
                String tip = tile.getTerritoryName() != null ? tile.getTerritoryName() : tile.getName();
                if (tile.getDefenseLevel() > 0) tip += " +" + tile.getDefenseLevel() + " def";
                dot.setToolTipText(tip);
                section.add(dot);
            }
            panel.add(section);
        }

        // Attackable territories
        if (!attackable.isEmpty()) {
            JPanel section = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            section.setBackground(ColorScheme.DARK_GRAY_COLOR);
            section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            JLabel label = new JLabel("\u2694 Attack (" + attackable.size() + "):");
            label.setForeground(COLOR_ATTACKABLE);
            label.setFont(label.getFont().deriveFont(10f));
            section.add(label);
            for (BoardTile tile : attackable) {
                JPanel dot = new JPanel();
                dot.setPreferredSize(new Dimension(10, 10));
                dot.setBackground(COLOR_ATTACKABLE);
                String tip = (tile.getTerritoryName() != null ? tile.getTerritoryName() : tile.getName());
                if (tile.getDefenseLevel() > 0) tip += " +" + tile.getDefenseLevel() + " def";
                dot.setToolTipText(tip);
                section.add(dot);
            }
            panel.add(section);
        }

        // Enemy (not adjacent)
        if (!enemyLocked.isEmpty()) {
            JPanel section = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            section.setBackground(ColorScheme.DARK_GRAY_COLOR);
            section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            JLabel label = new JLabel("Enemy (" + enemyLocked.size() + "):");
            label.setForeground(COLOR_LOCKED);
            label.setFont(label.getFont().deriveFont(10f));
            section.add(label);
            for (BoardTile tile : enemyLocked) {
                JPanel dot = new JPanel();
                dot.setPreferredSize(new Dimension(10, 10));
                dot.setBackground(COLOR_LOCKED);
                dot.setToolTipText(tile.getTerritoryName() != null ? tile.getTerritoryName() : tile.getName());
                section.add(dot);
            }
            panel.add(section);
        }

        return panel;
    }

    private JPanel renderMonopolyBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();
        int tilesPerRow = 8;
        int totalRows = (int) Math.ceil(tiles.size() / (double) tilesPerRow);
        int cellSize = calculateCellSize(tilesPerRow, totalRows);

        JPanel track = new JPanel(new GridLayout(totalRows, tilesPerRow, 1, 1));
        track.setBackground(ColorScheme.DARK_GRAY_COLOR);
        track.setBorder(new EmptyBorder(4, 4, 4, 4));

        for (int i = 0; i < totalRows * tilesPerRow; i++) {
            JPanel cell = new JPanel();
            cell.setPreferredSize(new Dimension(cellSize, cellSize));

            if (i < tiles.size()) {
                BoardTile tile = tiles.get(i);
                cell.setBackground(tile.isCompleted() ? COLOR_COMPLETED : COLOR_INCOMPLETE);
                cell.setToolTipText(tile.getName());
            } else {
                cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }

            track.add(cell);
        }

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(track);
        return wrapper;
    }

    // ===== TILE LIST =====

    private void renderTileList(BoardData board) {
        String gameType = board.getGameType();
        List<BoardTile> tiles = board.getTiles();

        if ("tilerace".equals(gameType)) {
            renderTileRaceList(tiles, board);
        } else if ("chipdrop".equals(gameType)) {
            renderChipDropList(tiles);
        } else if ("territory".equals(gameType)) {
            renderTerritoryList(tiles);
        } else if ("battleship".equals(gameType)) {
            renderBattleshipList(tiles);
        } else if ("delve".equals(gameType)) {
            renderDelveList(tiles, board);
        } else {
            renderDefaultList(tiles);
        }
    }

    private void renderTileRaceList(List<BoardTile> tiles, BoardData board) {
        // Current tile
        List<BoardTile> current = tiles.stream()
            .filter(t -> t.isCurrent() && t.getSpecial() == null && !t.isCompleted())
            .collect(Collectors.toList());
        if (!current.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u25B6 Current Tile", COLOR_CURRENT));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : current) {
                tilesPanel.add(createTileRow(tile, false, "\u25B6 "));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Opponent positions
        if (board.getOpponents() != null && !board.getOpponents().isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2716 Opponents", COLOR_OPPONENT));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardData.OpponentPosition opp : board.getOpponents()) {
                JPanel row = new JPanel(new BorderLayout());
                row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                row.setBorder(new EmptyBorder(4, 8, 4, 8));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
                JLabel label = new JLabel("\u2716 " + opp.getName());
                label.setForeground(COLOR_OPPONENT);
                label.setFont(label.getFont().deriveFont(11f));
                row.add(label, BorderLayout.WEST);
                JLabel posLabel = new JLabel("tile " + opp.getPosition());
                posLabel.setForeground(new Color(150, 150, 180));
                posLabel.setFont(posLabel.getFont().deriveFont(10f));
                row.add(posLabel, BorderLayout.EAST);
                tilesPanel.add(row);
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Remaining
        List<BoardTile> remaining = tiles.stream()
            .filter(t -> !t.isCompleted() && !t.isCurrent() && t.getSpecial() == null)
            .collect(Collectors.toList());
        if (!remaining.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u25CB Remaining (" + remaining.size() + ")", COLOR_INCOMPLETE));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : remaining) {
                tilesPanel.add(createTileRow(tile, false, null));
            }
        }
    }

    private void renderTerritoryList(List<BoardTile> tiles) {
        // Attackable territories (most important)
        List<BoardTile> attackable = tiles.stream()
            .filter(t -> t.isAttackable() && !t.isOurs())
            .collect(Collectors.toList());
        if (!attackable.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2694 Attackable (" + attackable.size() + ")", COLOR_ATTACKABLE));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : attackable) {
                String name = tile.getTerritoryName() != null ? tile.getTerritoryName() : tile.getName();
                String suffix = tile.getDefenseLevel() > 0 ? " [+" + tile.getDefenseLevel() + " def]" : "";
                JPanel row = new JPanel(new BorderLayout());
                row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                row.setBorder(new EmptyBorder(4, 8, 4, 8));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
                JLabel label = new JLabel("\u2694 " + name + suffix);
                label.setForeground(COLOR_ATTACKABLE);
                label.setFont(label.getFont().deriveFont(11f));
                row.add(label, BorderLayout.WEST);
                JLabel dropLabel = new JLabel(tile.getName());
                dropLabel.setForeground(new Color(150, 150, 180));
                dropLabel.setFont(dropLabel.getFont().deriveFont(9f));
                row.add(dropLabel, BorderLayout.EAST);
                tilesPanel.add(row);
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Our territories (for fortification)
        List<BoardTile> ours = tiles.stream().filter(BoardTile::isOurs).collect(Collectors.toList());
        if (!ours.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2713 Your Territories (" + ours.size() + ")", COLOR_COMPLETED));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : ours) {
                String name = tile.getTerritoryName() != null ? tile.getTerritoryName() : tile.getName();
                String suffix = tile.getDefenseLevel() > 0 ? " [+" + tile.getDefenseLevel() + " def]" : "";
                tilesPanel.add(createTileRow(tile, true, null));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Locked enemy territories
        List<BoardTile> locked = tiles.stream()
            .filter(t -> !t.isOurs() && !t.isAttackable())
            .collect(Collectors.toList());
        if (!locked.isEmpty()) {
            tilesPanel.add(createSectionHeader("\uD83D\uDD12 Out of Reach (" + locked.size() + ")", COLOR_LOCKED));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : locked) {
                tilesPanel.add(createTileRow(tile, false, null));
            }
        }
    }

    private void renderBattleshipList(List<BoardTile> tiles) {
        // Sunk ships
        List<BoardTile> sunk = tiles.stream()
            .filter(t -> "sunk".equals(t.getAttackResult()))
            .collect(Collectors.toList());
        if (!sunk.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2620 Sunk (" + sunk.size() + ")", COLOR_SUNK));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : sunk) {
                tilesPanel.add(createTileRow(tile, true, "\u2620 "));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Hits (not yet sunk)
        List<BoardTile> hits = tiles.stream()
            .filter(t -> "hit".equals(t.getAttackResult()))
            .collect(Collectors.toList());
        if (!hits.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2716 Hits (" + hits.size() + ")", COLOR_HIT));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : hits) {
                tilesPanel.add(createTileRow(tile, true, "\u2716 "));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Misses
        List<BoardTile> misses = tiles.stream()
            .filter(t -> "miss".equals(t.getAttackResult()))
            .collect(Collectors.toList());
        if (!misses.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u25CB Misses (" + misses.size() + ")", COLOR_MISS));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : misses) {
                tilesPanel.add(createTileRow(tile, false, "\u25CB "));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Remaining (not yet attacked)
        List<BoardTile> remaining = tiles.stream()
            .filter(t -> t.getAttackResult() == null)
            .collect(Collectors.toList());
        if (!remaining.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u25CB Remaining (" + remaining.size() + ")", COLOR_INCOMPLETE));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : remaining) {
                tilesPanel.add(createTileRow(tile, false, null));
            }
        }
    }

    private void renderChipDropList(List<BoardTile> tiles) {
        List<BoardTile> available = tiles.stream()
            .filter(t -> t.isAvailable() && !t.isCompleted())
            .collect(Collectors.toList());
        if (!available.isEmpty()) {
            tilesPanel.add(createSectionHeader("\uD83D\uDD13 Available (" + available.size() + ")", COLOR_AVAILABLE));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : available) {
                tilesPanel.add(createTileRow(tile, false, null));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> completed = tiles.stream()
            .filter(BoardTile::isCompleted)
            .collect(Collectors.toList());
        if (!completed.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2713 Claimed (" + completed.size() + ")", COLOR_COMPLETED));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : completed) {
                tilesPanel.add(createTileRow(tile, true, null));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> locked = tiles.stream()
            .filter(t -> !t.isAvailable() && !t.isCompleted())
            .collect(Collectors.toList());
        if (!locked.isEmpty()) {
            tilesPanel.add(createSectionHeader("\uD83D\uDD12 Locked (" + locked.size() + ")", COLOR_LOCKED));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : locked) {
                tilesPanel.add(createTileRow(tile, false, "\uD83D\uDD12 "));
            }
        }
    }

    private void renderDefaultList(List<BoardTile> tiles) {
        List<BoardTile> completed = tiles.stream()
            .filter(t -> t.isCompleted() && !t.isFree())
            .collect(Collectors.toList());
        if (!completed.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2713 Completed (" + completed.size() + ")", COLOR_COMPLETED));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : completed) {
                tilesPanel.add(createTileRow(tile, true, null));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> remaining = tiles.stream()
            .filter(t -> !t.isCompleted() && !t.isFree())
            .collect(Collectors.toList());
        if (!remaining.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u25CB Remaining (" + remaining.size() + ")", COLOR_INCOMPLETE));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : remaining) {
                tilesPanel.add(createTileRow(tile, false, null));
            }
        }
    }

    /**
     * The Delve's list is not a checklist. Its three groups answer three different
     * questions, and conflating them is what made the old panel useless here: the boss
     * table is what you should be killing right now, the objectives are the only drops
     * actually required, and the bonus drops are pure upside that nobody is assigned.
     */
    private void renderDelveList(List<BoardTile> tiles, BoardData board) {
        BoardData.DelveMeta dm = board.getDelveMeta();

        List<BoardData.DelveBoss> bosses = (dm != null && dm.getBosses() != null)
            ? dm.getBosses()
            : java.util.Collections.<BoardData.DelveBoss>emptyList();
        if (!bosses.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2694 Kill for supplies (" + bosses.size() + ")", COLOR_BRAND));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardData.DelveBoss boss : bosses) {
                tilesPanel.add(createDelveBossRow(boss));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        // Guardians and the warden: the only drops the Delve genuinely requires, and only
        // once the party has paid to open that room.
        List<BoardTile> required = tiles.stream()
            .filter(t -> !t.isOptional() && !t.isCompleted())
            .collect(Collectors.toList());
        if (!required.isEmpty()) {
            tilesPanel.add(createSectionHeader("\uD83D\uDDDD Must drop (" + required.size() + ")", COLOR_CURRENT));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : required) {
                tilesPanel.add(createTileRow(tile, false, null));
            }
            tilesPanel.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> bonus = tiles.stream()
            .filter(BoardTile::isOptional)
            .collect(Collectors.toList());
        if (!bonus.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2728 Bonus if you get lucky (" + bonus.size() + ")", COLOR_FREE));
            tilesPanel.add(Box.createVerticalStrut(2));
            tilesPanel.add(createDelveNote("Not required, not assigned \u2014 the plugin submits these automatically."));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : bonus) {
                tilesPanel.add(createDelveBonusRow(tile));
            }
        }
    }

    private JPanel createDelveBossRow(BoardData.DelveBoss boss) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JLabel nameLabel = new JLabel(boss.getName());
        nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(11f));
        row.add(nameLabel, BorderLayout.WEST);

        String right = boss.getRate() + "/kill";
        if (boss.getKills() > 0) right = boss.getKills() + " \u00D7 " + right;
        JLabel rateLabel = new JLabel(right);
        rateLabel.setForeground(boss.getKills() > 0 ? COLOR_COMPLETED : tierColor(boss.getTier()));
        rateLabel.setFont(rateLabel.getFont().deriveFont(10f));
        rateLabel.setToolTipText(boss.getKills() > 0
            ? boss.getKills() + " kills banked, worth " + (boss.getKills() * boss.getRate()) + " supplies"
            : "Worth " + boss.getRate() + " supplies per kill");
        row.add(rateLabel, BorderLayout.EAST);

        return row;
    }

    private JPanel createDelveBonusRow(BoardTile tile) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JLabel nameLabel = new JLabel(tile.getName());
        nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(11f));
        row.add(nameLabel, BorderLayout.WEST);

        if (tile.getTier() != null && !tile.getTier().isEmpty()) {
            JLabel tierLabel = new JLabel(tile.getTier());
            tierLabel.setForeground(new Color(150, 150, 180));
            tierLabel.setFont(tierLabel.getFont().deriveFont(10f));
            row.add(tierLabel, BorderLayout.EAST);
        }

        return row;
    }

    private JPanel createDelveNote(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(0, 8, 2, 8));
        // Swing has no text wrapping on JLabel; HTML with a width gives us one.
        JLabel label = new JLabel("<html><body style='width:" + (PANEL_WIDTH - 40) + "px'>" + text + "</body></html>");
        label.setForeground(new Color(130, 130, 150));
        label.setFont(label.getFont().deriveFont(9f));
        panel.add(label, BorderLayout.WEST);
        return panel;
    }

    private Color tierColor(String tier) {
        if ("hard".equals(tier)) return new Color(226, 120, 120);
        if ("easy".equals(tier)) return new Color(140, 190, 140);
        return new Color(220, 180, 60);
    }

    // ===== UI HELPERS =====

    private JPanel createSectionHeader(String text, Color color) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        panel.add(label, BorderLayout.WEST);

        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(60, 60, 70));
        panel.add(sep, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createTileRow(BoardTile tile, boolean completed, String prefix) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        String displayName = (prefix != null ? prefix : "") + tile.getName();
        if (completed && prefix == null) {
            displayName = "\u2713 " + tile.getName();
        }

        JLabel nameLabel = new JLabel(displayName);
        nameLabel.setForeground(completed ? new Color(120, 180, 120) : ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(11f));
        row.add(nameLabel, BorderLayout.WEST);

        if (tile.getQuantity() > 1) {
            String qtyText = tile.getCurrentQty() + "/" + tile.getQuantity();
            JLabel qtyLabel = new JLabel(qtyText);
            Color qtyColor = tile.getCurrentQty() >= tile.getQuantity()
                ? new Color(120, 180, 120)
                : tile.getCurrentQty() > 0
                    ? new Color(220, 180, 60)
                    : new Color(150, 150, 180);
            qtyLabel.setForeground(qtyColor);
            qtyLabel.setFont(qtyLabel.getFont().deriveFont(10f));
            row.add(qtyLabel, BorderLayout.EAST);
        }

        return row;
    }

    private String formatGameType(String gameType) {
        if (gameType == null) return "";
        switch (gameType) {
            case "bingo": return "Bingo";
            case "tilerace": return "Tile Race";
            case "territory": return "Territory War";
            case "chipdrop": return "Chip Drop";
            case "battleship": return "Battleship";
            case "monopoly": return "Boss Tycoon";
            case "delve": return "The Delve";
            default: return gameType;
        }
    }
}
