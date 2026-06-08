package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * RuneLite sidebar panel showing the full board status —
 * which tiles are completed, which are remaining, and current progress.
 */
@Slf4j
public class BirdhousePanel extends PluginPanel {

    private final BirdhouseConfig config;
    private final DropMatcher dropMatcher;
    private final BirdhouseApiClient apiClient;

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
        titleLabel.setForeground(new Color(93, 164, 196));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        refreshButton = new JButton("↻");
        refreshButton.setToolTipText("Refresh board");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> refreshBoard());
        headerPanel.add(refreshButton, BorderLayout.EAST);

        // Status area
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

        // Tiles list
        tilesPanel = new JPanel();
        tilesPanel.setLayout(new BoxLayout(tilesPanel, BoxLayout.Y_AXIS));
        tilesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JScrollPane scrollPane = new JScrollPane(tilesPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // Assemble
        JPanel topSection = new JPanel(new BorderLayout());
        topSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topSection.add(headerPanel, BorderLayout.NORTH);
        topSection.add(statusPanel, BorderLayout.SOUTH);

        add(topSection, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void refreshBoard() {
        String roomCode = config.roomCode();
        if (roomCode == null || roomCode.isEmpty()) {
            statusLabel.setText("No room code set");
            progressLabel.setText("");
            tilesPanel.removeAll();
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
            tilesPanel.removeAll();
            tilesPanel.revalidate();
            return;
        }

        int total = tiles.size();
        int completed = (int) tiles.stream().filter(BoardTile::isCompleted).count();
        int remaining = total - completed;

        statusLabel.setText("Room: " + roomCode + " • " + board.getGameType());
        progressLabel.setText(completed + " / " + total + " complete (" + remaining + " remaining)");

        tilesPanel.removeAll();

        // Completed section
        if (completed > 0) {
            tilesPanel.add(createSectionHeader("✓ Completed (" + completed + ")", new Color(100, 200, 100)));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : tiles) {
                if (tile.isCompleted()) {
                    tilesPanel.add(createTileRow(tile, true));
                }
            }
            tilesPanel.add(Box.createVerticalStrut(12));
        }

        // Remaining section
        if (remaining > 0) {
            tilesPanel.add(createSectionHeader("○ Remaining (" + remaining + ")", new Color(255, 200, 100)));
            tilesPanel.add(Box.createVerticalStrut(4));
            for (BoardTile tile : tiles) {
                if (!tile.isCompleted()) {
                    tilesPanel.add(createTileRow(tile, false));
                }
            }
        }

        tilesPanel.revalidate();
        tilesPanel.repaint();
    }

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

    private JPanel createTileRow(BoardTile tile, boolean completed) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JLabel nameLabel = new JLabel(tile.getName());
        nameLabel.setForeground(completed ? new Color(120, 180, 120) : ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(11f));

        if (completed) {
            nameLabel.setText("✓ " + tile.getName());
        }

        row.add(nameLabel, BorderLayout.WEST);

        if (tile.getQuantity() > 1) {
            JLabel qtyLabel = new JLabel("x" + tile.getQuantity());
            qtyLabel.setForeground(new Color(150, 150, 180));
            qtyLabel.setFont(qtyLabel.getFont().deriveFont(10f));
            row.add(qtyLabel, BorderLayout.EAST);
        }

        return row;
    }
}
