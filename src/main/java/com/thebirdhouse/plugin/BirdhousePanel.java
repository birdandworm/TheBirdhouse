package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RuneLite sidebar panel showing a mini board visual at the top
 * and a detailed tile list below, adapting to each game mode.
 */
@Slf4j
public class BirdhousePanel extends PluginPanel {

    private static final Color COLOR_COMPLETED = new Color(100, 200, 100);
    private static final Color COLOR_CURRENT = new Color(255, 200, 100);
    private static final Color COLOR_AVAILABLE = new Color(255, 200, 100);
    private static final Color COLOR_LOCKED = new Color(60, 60, 70);
    private static final Color COLOR_INCOMPLETE = new Color(120, 120, 140);
    private static final Color COLOR_FREE = new Color(93, 164, 196);
    private static final Color COLOR_BRAND = new Color(93, 164, 196);

    private final BirdhouseConfig config;
    private final DropMatcher dropMatcher;
    private final BirdhouseApiClient apiClient;

    private final JPanel mainPanel;
    private final JPanel boardPanel;
    private final JPanel tilesPanel;
    private final JLabel statusLabel;
    private final JLabel progressLabel;
    private final JButton refreshButton;

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
        JPanel statusPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        statusPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        statusPanel.setBorder(new EmptyBorder(8, 0, 8, 0));

        statusLabel = new JLabel("Not connected");
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusPanel.add(statusLabel);

        progressLabel = new JLabel("");
        progressLabel.setForeground(new Color(150, 200, 100));
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusPanel.add(progressLabel);

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

        // Main content panel
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainPanel.add(boardPanel);
        mainPanel.add(scrollPane);

        // Top section
        JPanel topSection = new JPanel(new BorderLayout());
        topSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topSection.add(headerPanel, BorderLayout.NORTH);
        topSection.add(statusPanel, BorderLayout.SOUTH);

        add(topSection, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);
    }

    public void refreshBoard() {
        String roomCode = config.roomCode();
        if (roomCode == null || roomCode.isEmpty()) {
            statusLabel.setText("No room code set");
            progressLabel.setText("");
            boardPanel.removeAll();
            tilesPanel.removeAll();
            boardPanel.revalidate();
            tilesPanel.revalidate();
            return;
        }

        statusLabel.setText("Loading...");
        refreshButton.setEnabled(false);

        apiClient.fetchBoard(roomCode).thenAccept(board -> {
            SwingUtilities.invokeLater(() -> {
                refreshButton.setEnabled(true);
                if (board == null) {
                    statusLabel.setText("Failed to load board");
                    progressLabel.setText("");
                    return;
                }
                updatePanel(board, roomCode);
            });
        });
    }

    private void updatePanel(BoardData board, String roomCode) {
        List<BoardTile> tiles = board.getTiles();
        if (tiles == null || tiles.isEmpty()) {
            statusLabel.setText("Room: " + roomCode + " (no tiles)");
            progressLabel.setText("");
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

        statusLabel.setText("Room: " + roomCode + " \u2022 " + formatGameType(gameType));
        progressLabel.setText(completed + " / " + total + " complete (" + remaining + " remaining)");

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
            default:
                return new JPanel();
        }
    }

    private JPanel renderGridBoard(BoardData board) {
        int rows = board.getRows();
        int cols = board.getCols();
        if (rows == 0 || cols == 0) return new JPanel();

        JPanel grid = new JPanel(new GridLayout(rows, cols, 2, 2));
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<BoardTile> tiles = board.getTiles();
        int tileIdx = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                JPanel cell = new JPanel();
                cell.setPreferredSize(new Dimension(18, 18));

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

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(grid);
        return wrapper;
    }

    private JPanel renderChipDropBoard(BoardData board) {
        int rows = board.getRows();
        int cols = board.getCols();
        if (rows == 0 || cols == 0) return new JPanel();

        JPanel grid = new JPanel(new GridLayout(rows, cols, 2, 2));
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<BoardTile> tiles = board.getTiles();
        int tileIdx = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                JPanel cell = new JPanel();
                cell.setPreferredSize(new Dimension(18, 18));

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

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(grid);
        return wrapper;
    }

    private JPanel renderTileRaceBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();
        int tilesPerRow = 10;
        int totalRows = (int) Math.ceil(tiles.size() / (double) tilesPerRow);

        JPanel track = new JPanel(new GridLayout(totalRows, tilesPerRow, 2, 2));
        track.setBackground(ColorScheme.DARK_GRAY_COLOR);
        track.setBorder(new EmptyBorder(4, 4, 4, 4));

        for (int i = 0; i < totalRows * tilesPerRow; i++) {
            JPanel cell = new JPanel();
            cell.setPreferredSize(new Dimension(16, 16));

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
                    cell.setToolTipText("\u25B6 " + tile.getName() + " (current)");
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

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(track);
        return wrapper;
    }

    private JPanel renderTerritoryBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        // Group by region
        java.util.Map<String, List<BoardTile>> byRegion = tiles.stream()
            .collect(Collectors.groupingBy(t -> t.getRegion() != null ? t.getRegion() : "Unknown"));

        for (java.util.Map.Entry<String, List<BoardTile>> entry : byRegion.entrySet()) {
            JPanel regionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            regionRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
            regionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

            JLabel regionLabel = new JLabel(entry.getKey() + ":");
            regionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            regionLabel.setFont(regionLabel.getFont().deriveFont(10f));
            regionRow.add(regionLabel);

            for (BoardTile tile : entry.getValue()) {
                JPanel dot = new JPanel();
                dot.setPreferredSize(new Dimension(10, 10));
                dot.setBackground(tile.isCompleted() ? COLOR_COMPLETED : COLOR_INCOMPLETE);
                dot.setToolTipText(tile.getName());
                regionRow.add(dot);
            }

            panel.add(regionRow);
        }

        return panel;
    }

    private JPanel renderMonopolyBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();
        int tilesPerRow = 8;
        int totalRows = (int) Math.ceil(tiles.size() / (double) tilesPerRow);

        JPanel track = new JPanel(new GridLayout(totalRows, tilesPerRow, 2, 2));
        track.setBackground(ColorScheme.DARK_GRAY_COLOR);
        track.setBorder(new EmptyBorder(4, 4, 4, 4));

        for (int i = 0; i < totalRows * tilesPerRow; i++) {
            JPanel cell = new JPanel();
            cell.setPreferredSize(new Dimension(18, 18));

            if (i < tiles.size()) {
                BoardTile tile = tiles.get(i);
                cell.setBackground(tile.isCompleted() ? COLOR_COMPLETED : COLOR_INCOMPLETE);
                cell.setToolTipText(tile.getName());
            } else {
                cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }

            track.add(cell);
        }

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(track);
        return wrapper;
    }

    // ===== TILE LIST =====

    private void renderTileList(BoardData board) {
        String gameType = board.getGameType();
        List<BoardTile> tiles = board.getTiles();

        if ("tilerace".equals(gameType)) {
            renderTileRaceList(tiles);
        } else if ("chipdrop".equals(gameType)) {
            renderChipDropList(tiles);
        } else {
            renderDefaultList(tiles);
        }
    }

    private void renderTileRaceList(List<BoardTile> tiles) {
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

        // Completed
        List<BoardTile> completed = tiles.stream()
            .filter(t -> t.isCompleted() && t.getSpecial() == null)
            .collect(Collectors.toList());
        if (!completed.isEmpty()) {
            tilesPanel.add(createSectionHeader("\u2713 Completed (" + completed.size() + ")", COLOR_COMPLETED));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : completed) {
                tilesPanel.add(createTileRow(tile, true, null));
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

    private void renderChipDropList(List<BoardTile> tiles) {
        // Available (unlocked)
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

        // Completed (claimed)
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

        // Locked
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
            JLabel qtyLabel = new JLabel("x" + tile.getQuantity());
            qtyLabel.setForeground(new Color(150, 150, 180));
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
            default: return gameType;
        }
    }
}
