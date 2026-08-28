package com.thebirdhouse.plugin;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Draws a board and its tile list into whatever space the caller has.
 *
 * The sidebar and the pop-out window show the same boards at wildly different scales,
 * so every size decision here derives from the space handed in rather than from the
 * 225px sidebar that used to be the only consumer.
 */
class BoardRenderer {

    static final Color COLOR_COMPLETED = new Color(100, 200, 100);
    static final Color COLOR_CURRENT = new Color(255, 200, 100);
    static final Color COLOR_AVAILABLE = new Color(255, 200, 100);
    static final Color COLOR_LOCKED = new Color(60, 60, 70);
    static final Color COLOR_INCOMPLETE = new Color(120, 120, 140);
    static final Color COLOR_FREE = new Color(93, 164, 196);
    static final Color COLOR_BRAND = new Color(93, 164, 196);
    static final Color COLOR_OPPONENT = new Color(220, 80, 80);
    static final Color COLOR_HIT = new Color(220, 60, 60);
    static final Color COLOR_SUNK = new Color(160, 40, 40);
    static final Color COLOR_MISS = new Color(80, 80, 120);
    static final Color COLOR_ATTACKABLE = new Color(220, 140, 60);
    static final Color COLOR_MUTED = new Color(150, 150, 170);
    static final Color COLOR_HOT = new Color(240, 120, 60);
    /** Defence cells carry only a hit/miss glyph, so they never need a named tile's room. */
    private static final int MAX_DEFENSE_CELL_SIZE = 48;
    private static final int TERRITORY_CARD_WIDTH = 190;
    private static final int BOUNTY_CARD_WIDTH = 215;
    /** Beyond this a closesAt is a sentinel for "rotates out", not a real date. */
    private static final long NO_DEADLINE_AFTER_MS = 4_000_000_000_000L;

    private final int availableWidth;
    /** 0 means "as tall as it needs to be" — the sidebar scrolls, the window does not. */
    private final int availableHeight;
    private final int maxCellSize;
    /** Detailed cells carry the tile name and progress; small ones are colour-coded squares. */
    private final boolean detailed;

    private Consumer<BoardTile> tileClickListener;

    BoardRenderer(int availableWidth, int availableHeight, int maxCellSize, boolean detailed) {
        this.availableWidth = availableWidth;
        this.availableHeight = availableHeight;
        this.maxCellSize = maxCellSize;
        this.detailed = detailed;
    }

    void setTileClickListener(Consumer<BoardTile> listener) {
        this.tileClickListener = listener;
    }

    // ===== BOARD =====

    JPanel renderBoard(BoardData board) {
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
            case "bounty":
                return renderBountyBoard(board);
            default:
                return unsupportedNotice(gameType);
        }
    }

    /**
     * Modes without a board renderer yet (Bounty Board, Clue Trail, The Maze) still get a
     * usable tile list, so the board area says so rather than sitting empty.
     */
    private JPanel unsupportedNotice(String gameType) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        if (!detailed) {
            return panel;
        }
        String label = formatGameType(gameType);
        JLabel notice = new JLabel("<html><div style='text-align:center'>No board view for "
            + escapeHtml(label.isEmpty() ? "this game mode" : label)
            + " yet.<br>Your tiles are listed on the right, and the full board is on the website.</div></html>",
            SwingConstants.CENTER);
        notice.setForeground(COLOR_MUTED);
        notice.setBorder(new EmptyBorder(40, 20, 40, 20));
        panel.add(notice, BorderLayout.CENTER);
        return panel;
    }

    private int calculateCellSize(int cols, int rows) {
        if (cols <= 0) return maxCellSize;
        int byWidth = (availableWidth - 28) / cols - 2;
        int size = byWidth;
        if (availableHeight > 0 && rows > 0) {
            size = Math.min(size, (availableHeight - 28) / rows - 2);
        }
        return Math.min(Math.max(8, size), maxCellSize);
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
        supplies.setFont(supplies.getFont().deriveFont(Font.BOLD, fs(26f)));
        supplies.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(supplies);

        JLabel caption = new JLabel("supplies to spend");
        caption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        caption.setFont(caption.getFont().deriveFont(fs(10f)));
        caption.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(caption);

        panel.add(Box.createVerticalStrut(4));

        JLabel split = new JLabel("\u2694 " + dm.getFromKills()
            + "   \uD83D\uDCE6 " + dm.getBonusEarned()
            + "   \u2212" + dm.getSpent() + " spent");
        split.setForeground(new Color(150, 150, 180));
        split.setFont(split.getFont().deriveFont(fs(10f)));
        split.setAlignmentX(Component.CENTER_ALIGNMENT);
        split.setToolTipText("Earned from kills, plus bonus drops, minus what the party has spent");
        panel.add(split);

        String progress = dm.getRoomsOpened() + " rooms opened";
        if (dm.isVaultCleared()) progress = "\uD83C\uDFC6 Vault cracked \u2022 " + progress;
        JLabel rooms = new JLabel(progress);
        rooms.setForeground(dm.isVaultCleared() ? COLOR_COMPLETED : new Color(150, 150, 180));
        rooms.setFont(rooms.getFont().deriveFont(fs(10f)));
        rooms.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(rooms);

        return panel;
    }

    /**
     * The Bounty Board is a rotating set of live objectives rather than a grid, and the
     * only decision it offers is which one to chase next. That turns on two numbers the
     * other modes don't have: what it pays, and whether anyone has claimed it yet — the
     * first claim takes the full payout and everyone after gets a consolation slice.
     */
    private JPanel renderBountyBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();

        if (!detailed) {
            long claimed = tiles.stream().filter(BoardTile::isCompleted).count();
            JPanel summary = new JPanel();
            summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
            summary.setBackground(ColorScheme.DARKER_GRAY_COLOR);

            JLabel count = new JLabel(tiles.size() + " live");
            count.setForeground(COLOR_BRAND);
            count.setFont(count.getFont().deriveFont(Font.BOLD, 26f));
            count.setAlignmentX(Component.CENTER_ALIGNMENT);
            summary.add(count);

            JLabel caption = new JLabel(claimed + " claimed by you");
            caption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            caption.setFont(caption.getFont().deriveFont(10f));
            caption.setAlignmentX(Component.CENTER_ALIGNMENT);
            summary.add(caption);

            return summary;
        }

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(6, 6, 6, 6));

        List<BoardTile> ordered = orderBounties(tiles);
        int cols = Math.max(1, (availableWidth - 20) / BOUNTY_CARD_WIDTH);
        int rows = (int) Math.ceil(ordered.size() / (double) cols);
        JPanel grid = new JPanel(new GridLayout(Math.max(1, rows), cols, 4, 4));
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (BoardTile tile : ordered) {
            grid.add(bountyCard(tile));
        }
        for (int i = ordered.size(); i < Math.max(1, rows) * cols; i++) {
            JPanel filler = new JPanel();
            filler.setBackground(ColorScheme.DARK_GRAY_COLOR);
            grid.add(filler);
        }

        panel.add(grid);
        return panel;
    }

    /** Unclaimed before claimed, first-claim-still-open before contested, then by payout. */
    private List<BoardTile> orderBounties(List<BoardTile> tiles) {
        List<BoardTile> ordered = new ArrayList<>(tiles);
        ordered.sort((a, b) -> {
            if (a.isCompleted() != b.isCompleted()) {
                return a.isCompleted() ? 1 : -1;
            }
            boolean aOpen = a.getClaimsSoFar() == 0;
            boolean bOpen = b.getClaimsSoFar() == 0;
            if (aOpen != bOpen) {
                return aOpen ? -1 : 1;
            }
            return Integer.compare(b.getPoints(), a.getPoints());
        });
        return ordered;
    }

    private JPanel bountyCard(BoardTile tile) {
        boolean hot = "hot".equals(tile.getTier());
        Color state;
        if (tile.isCompleted()) {
            state = COLOR_COMPLETED;
        } else if (hot) {
            state = COLOR_HOT;
        } else if (tile.getClaimsSoFar() == 0) {
            state = COLOR_BRAND;
        } else {
            state = COLOR_INCOMPLETE;
        }

        Color bg = tint(state);
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(bg);
        card.setBorder(new CompoundBorder(new LineBorder(state, hot ? 2 : 1), new EmptyBorder(6, 8, 6, 8)));
        card.setPreferredSize(new Dimension(BOUNTY_CARD_WIDTH, 96));

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(bg);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JLabel points = new JLabel(tile.getPoints() + " pts");
        points.setForeground(state);
        points.setFont(points.getFont().deriveFont(Font.BOLD, 14f));
        top.add(points, BorderLayout.WEST);

        if (hot) {
            JLabel badge = new JLabel("HOT");
            badge.setForeground(COLOR_HOT);
            badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10f));
            top.add(badge, BorderLayout.EAST);
        }
        card.add(top);

        JLabel name = new JLabel("<html><div style='width:" + (BOUNTY_CARD_WIDTH - 24) + "px'>"
            + escapeHtml(tile.getName()) + "</div></html>");
        name.setForeground(Color.WHITE);
        name.setFont(name.getFont().deriveFont(12f));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(name);

        JLabel status = new JLabel(bountyStatus(tile));
        status.setForeground(tile.isCompleted() ? COLOR_COMPLETED : COLOR_MUTED);
        status.setFont(status.getFont().deriveFont(Font.BOLD, 10f));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(status);

        card.setToolTipText(tile.getName() + " \u2014 " + tile.getPoints() + " points");
        attachClick(card, tile, bg);
        return card;
    }

    private String bountyStatus(BoardTile tile) {
        StringBuilder sb = new StringBuilder();
        if (tile.isCompleted()) {
            sb.append("\u2713 claimed by you");
        } else if (tile.getClaimsSoFar() == 0) {
            sb.append("first claim open");
        } else {
            sb.append(tile.getClaimsSoFar()).append(" already claimed");
        }
        if (tile.getQuantity() > 1) {
            sb.append("  \u2022  ").append(tile.getCurrentQty()).append("/").append(tile.getQuantity());
        }
        String closing = bountyClosing(tile);
        if (closing != null) {
            sb.append("  \u2022  ").append(closing);
        }
        return sb.toString();
    }

    /**
     * A live bounty is sent with closesAt at the far end of the number range because it
     * rotates out rather than expiring, so only a plausible timestamp becomes a countdown.
     */
    private String bountyClosing(BoardTile tile) {
        Long closesAt = tile.getClosesAt();
        if (closesAt == null || closesAt > NO_DEADLINE_AFTER_MS) {
            return null;
        }
        long remaining = closesAt - System.currentTimeMillis();
        if (remaining <= 0) {
            return "closed";
        }
        long hours = remaining / (1000 * 60 * 60);
        if (hours >= 1) {
            return hours + "h left";
        }
        return Math.max(1, remaining / (1000 * 60)) + "m left";
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
                BoardTile tile = null;
                Color state = COLOR_LOCKED;

                if (tileIdx < tiles.size()) {
                    BoardTile candidate = tiles.get(tileIdx);
                    String expectedKey = r + "-" + c;
                    if (expectedKey.equals(candidate.getKey())) {
                        tile = candidate;
                        if (tile.isFree()) {
                            state = COLOR_FREE;
                        } else if (tile.isCompleted()) {
                            state = COLOR_COMPLETED;
                        } else {
                            state = COLOR_INCOMPLETE;
                        }
                        tileIdx++;
                    }
                }

                grid.add(createCell(cellSize, state, tile,
                    tile != null ? tile.getName() : null,
                    progressText(tile),
                    tile != null ? tile.getName() : null));
            }
        }

        return center(grid);
    }

    private JPanel renderBattleshipGrids(BoardData board) {
        int rows = board.getRows();
        int cols = board.getCols();
        // Two stacked grids share the height, so neither may claim all of it.
        int cellSize = calculateCellSize(cols, rows * 2);

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel attackLabel = new JLabel("Your Attacks:");
        attackLabel.setForeground(COLOR_BRAND);
        attackLabel.setFont(attackLabel.getFont().deriveFont(Font.BOLD, fs(10f)));
        attackLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        container.add(attackLabel);
        container.add(Box.createVerticalStrut(2));

        JPanel attackGrid = new JPanel(new GridLayout(rows, cols, 1, 1));
        attackGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        List<BoardTile> tiles = board.getTiles();
        int tileIdx = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (tileIdx >= tiles.size()) {
                    attackGrid.add(createCell(cellSize, COLOR_LOCKED, null, null, null, null));
                    continue;
                }
                BoardTile tile = tiles.get(tileIdx++);
                String result = tile.getAttackResult();
                Color state;
                String tip;
                String badge;
                if ("hit".equals(result) || "sunk".equals(result)) {
                    boolean isSunk = "sunk".equals(result);
                    state = isSunk ? COLOR_SUNK : COLOR_HIT;
                    tip = (isSunk ? "\u2620 SUNK: " : "\u2716 HIT: ") + tile.getName();
                    badge = isSunk ? "\u2620 sunk" : "\u2716 hit";
                } else if ("miss".equals(result)) {
                    state = COLOR_MISS;
                    tip = "\u25CB Miss";
                    badge = "\u25CB miss";
                } else {
                    state = COLOR_INCOMPLETE;
                    tip = tile.getName();
                    badge = progressText(tile);
                }
                attackGrid.add(createCell(cellSize, state, tile, tile.getName(), badge, tip));
            }
        }
        container.add(center(attackGrid));

        BoardData.BattleshipMeta meta = board.getBattleshipMeta();
        if (meta != null && meta.getDefenseGrid() != null) {
            container.add(Box.createVerticalStrut(6));
            JLabel defLabel = new JLabel("Your Fleet:");
            defLabel.setForeground(COLOR_BRAND);
            defLabel.setFont(defLabel.getFont().deriveFont(Font.BOLD, fs(10f)));
            defLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            container.add(defLabel);
            container.add(Box.createVerticalStrut(2));

            java.util.Map<String, String> defGrid = meta.getDefenseGrid();
            int defCell = detailed ? Math.min(cellSize, MAX_DEFENSE_CELL_SIZE) : cellSize;
            JPanel defenseGrid = new JPanel(new GridLayout(rows, cols, 1, 1));
            defenseGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    String defResult = defGrid.get(r + "-" + c);
                    Color state;
                    String tip;
                    String glyph;
                    if ("hit".equals(defResult) || "sunk".equals(defResult)) {
                        boolean isSunk = "sunk".equals(defResult);
                        state = isSunk ? COLOR_SUNK : COLOR_HIT;
                        tip = isSunk ? "\u2620 Enemy sunk your ship!" : "Enemy hit here!";
                        glyph = isSunk ? "\u2620" : "\u2716";
                    } else if ("miss".equals(defResult)) {
                        state = COLOR_MISS;
                        tip = "Enemy missed";
                        glyph = "\u25CB";
                    } else {
                        state = new Color(50, 70, 90);
                        tip = "Safe";
                        glyph = null;
                    }
                    defenseGrid.add(createCell(defCell, state, null, null, glyph, tip));
                }
            }
            container.add(center(defenseGrid));
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
                BoardTile tile = null;
                Color state = COLOR_LOCKED;
                String tip = null;

                if (tileIdx < tiles.size()) {
                    BoardTile candidate = tiles.get(tileIdx);
                    if ((r + "-" + c).equals(candidate.getKey())) {
                        tile = candidate;
                        if (tile.isCompleted()) {
                            state = COLOR_COMPLETED;
                        } else if (tile.isAvailable()) {
                            state = COLOR_AVAILABLE;
                        } else {
                            state = COLOR_LOCKED;
                        }
                        tip = tile.getName() + (tile.isAvailable() ? " (available)" : tile.isCompleted() ? " (claimed)" : " (locked)");
                        tileIdx++;
                    }
                }

                grid.add(createCell(cellSize, state, tile,
                    tile != null ? tile.getName() : null, progressText(tile), tip));
            }
        }

        return center(grid);
    }

    private JPanel renderTileRaceBoard(BoardData board) {
        List<BoardTile> tiles = board.getTiles();
        int tilesPerRow = Math.min(detailed ? 8 : 12, Math.max(1, tiles.size()));
        int totalRows = (int) Math.ceil(tiles.size() / (double) tilesPerRow);
        int cellSize = calculateCellSize(tilesPerRow, totalRows);

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
            if (i >= tiles.size()) {
                track.add(createCell(cellSize, ColorScheme.DARK_GRAY_COLOR, null, null, null, null));
                continue;
            }

            BoardTile tile = tiles.get(i);
            String special = tile.getSpecial();
            Color state;
            String tip;
            String badge = progressText(tile);

            if ("start".equals(special)) {
                state = COLOR_FREE;
                tip = "Start";
            } else if ("finish".equals(special)) {
                state = new Color(200, 100, 200);
                tip = "Finish";
            } else if ("forward".equals(special) || "backward".equals(special)) {
                state = new Color(180, 140, 80);
                tip = tile.getName();
            } else if (tile.isCurrent()) {
                state = COLOR_CURRENT;
                tip = "\u25B6 " + tile.getName() + " (YOU)";
                badge = "\u25B6 you";
            } else if (opponentPositions.contains(i)) {
                state = COLOR_OPPONENT;
                tip = "\u2716 " + tile.getName() + " (" + getOpponentNamesAt(board, i) + ")";
            } else if (tile.isCompleted()) {
                state = COLOR_COMPLETED;
                tip = "\u2713 " + tile.getName();
            } else {
                state = COLOR_INCOMPLETE;
                tip = tile.getName();
            }

            track.add(createCell(cellSize, state, tile, tile.getName(), badge, tip));
        }

        return center(track);
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

        if (detailed) {
            return renderTerritoryCards(board, tiles);
        }

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        List<BoardTile> ourTiles = tiles.stream().filter(BoardTile::isOurs).collect(Collectors.toList());
        List<BoardTile> attackable = tiles.stream().filter(t -> t.isAttackable() && !t.isOurs()).collect(Collectors.toList());
        List<BoardTile> enemyLocked = tiles.stream().filter(t -> !t.isOurs() && !t.isAttackable()).collect(Collectors.toList());

        addTerritoryRow(panel, "Yours (" + ourTiles.size() + "):", COLOR_COMPLETED, ourTiles, true);
        addTerritoryRow(panel, "\u2694 Attack (" + attackable.size() + "):", COLOR_ATTACKABLE, attackable, true);
        addTerritoryRow(panel, "Enemy (" + enemyLocked.size() + "):", COLOR_LOCKED, enemyLocked, false);

        return panel;
    }

    /**
     * Territory War has no grid to draw — adjacency is the map — so at window size each
     * territory becomes a card carrying the three things that decide a move: who holds it,
     * how well defended it is, and which drop takes it.
     */
    private JPanel renderTerritoryCards(BoardData board, List<BoardTile> tiles) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(6, 6, 6, 6));

        addTerritoryGroup(panel, board, "\u2694 Attackable", COLOR_ATTACKABLE,
            tiles.stream().filter(t -> t.isAttackable() && !t.isOurs()).collect(Collectors.toList()));
        addTerritoryGroup(panel, board, "\u2713 Yours", COLOR_COMPLETED,
            tiles.stream().filter(BoardTile::isOurs).collect(Collectors.toList()));
        addTerritoryGroup(panel, board, "\uD83D\uDD12 Out of reach", COLOR_LOCKED,
            tiles.stream().filter(t -> !t.isOurs() && !t.isAttackable()).collect(Collectors.toList()));

        return panel;
    }

    private void addTerritoryGroup(JPanel parent, BoardData board, String title, Color color,
                                   List<BoardTile> tiles) {
        if (tiles.isEmpty()) {
            return;
        }

        JPanel header = createSectionHeader(title + " (" + tiles.size() + ")", color);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(header);
        parent.add(Box.createVerticalStrut(4));

        int cols = Math.max(1, (availableWidth - 20) / TERRITORY_CARD_WIDTH);
        int rows = (int) Math.ceil(tiles.size() / (double) cols);
        JPanel grid = new JPanel(new GridLayout(rows, cols, 4, 4));
        grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (BoardTile tile : tiles) {
            grid.add(territoryCard(tile, board, color));
        }
        // GridLayout stretches its last row's cells otherwise.
        for (int i = tiles.size(); i < rows * cols; i++) {
            JPanel filler = new JPanel();
            filler.setBackground(ColorScheme.DARK_GRAY_COLOR);
            grid.add(filler);
        }

        parent.add(grid);
        parent.add(Box.createVerticalStrut(10));
    }

    private JPanel territoryCard(BoardTile tile, BoardData board, Color color) {
        Color bg = tint(color);
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(bg);
        card.setBorder(new CompoundBorder(new LineBorder(color, 1), new EmptyBorder(6, 8, 6, 8)));
        card.setPreferredSize(new Dimension(TERRITORY_CARD_WIDTH, 82));

        String name = tile.getTerritoryName() != null && !tile.getTerritoryName().isEmpty()
            ? tile.getTerritoryName() : tile.getName();
        JLabel nameLabel = new JLabel(name);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(nameLabel);

        JLabel dropLabel = new JLabel(tile.getName());
        dropLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        dropLabel.setFont(dropLabel.getFont().deriveFont(11f));
        dropLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(dropLabel);

        StringBuilder meta = new StringBuilder();
        String holder = territoryHolder(tile, board);
        if (holder != null) {
            meta.append(holder);
        }
        if (tile.getDefenseLevel() > 0) {
            if (meta.length() > 0) meta.append("  \u2022  ");
            meta.append("+").append(tile.getDefenseLevel()).append(" def");
        }
        if (meta.length() > 0) {
            JLabel metaLabel = new JLabel(meta.toString());
            metaLabel.setForeground(color);
            metaLabel.setFont(metaLabel.getFont().deriveFont(Font.BOLD, 10f));
            metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(metaLabel);
        }

        card.setToolTipText(name + " \u2014 " + tile.getName());
        attachClick(card, tile, bg);
        return card;
    }

    /** Display name of whoever currently holds a territory, or null when unknown. */
    private String territoryHolder(BoardTile tile, BoardData board) {
        BoardData.TerritoryMeta meta = board.getTerritoryMeta();
        if (meta == null || meta.getOwners() == null) {
            return null;
        }
        BoardData.TerritoryOwner owner = meta.getOwners().get(tile.getKey());
        if (owner == null || owner.getOwner() == null) {
            return "unclaimed";
        }
        if (meta.getOwnerNames() != null) {
            String display = meta.getOwnerNames().get(owner.getOwner());
            if (display != null && !display.isEmpty()) {
                return display;
            }
        }
        return owner.getOwner();
    }

    private void addTerritoryRow(JPanel parent, String title, Color color, List<BoardTile> tiles, boolean showDefense) {
        if (tiles.isEmpty()) return;
        int dot = detailed ? 18 : 10;

        JPanel section = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        section.setBackground(ColorScheme.DARK_GRAY_COLOR);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, detailed ? Integer.MAX_VALUE : 26));

        JLabel label = new JLabel(title);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(fs(10f)));
        section.add(label);

        for (BoardTile tile : tiles) {
            JPanel cell = new JPanel();
            cell.setPreferredSize(new Dimension(dot, dot));
            cell.setBackground(color);
            String tip = tile.getTerritoryName() != null ? tile.getTerritoryName() : tile.getName();
            if (showDefense && tile.getDefenseLevel() > 0) tip += " +" + tile.getDefenseLevel() + " def";
            cell.setToolTipText(tip);
            attachClick(cell, tile, color);
            section.add(cell);
        }
        parent.add(section);
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
            if (i >= tiles.size()) {
                track.add(createCell(cellSize, ColorScheme.DARK_GRAY_COLOR, null, null, null, null));
                continue;
            }
            BoardTile tile = tiles.get(i);
            Color state = tile.isCompleted() ? COLOR_COMPLETED : COLOR_INCOMPLETE;
            track.add(createCell(cellSize, state, tile, tile.getName(), progressText(tile), tile.getName()));
        }

        return center(track);
    }

    // ===== TILE LIST =====

    void renderTileList(BoardData board, JPanel container) {
        String gameType = board.getGameType();
        List<BoardTile> tiles = board.getTiles();

        if ("tilerace".equals(gameType)) {
            renderTileRaceList(tiles, board, container);
        } else if ("chipdrop".equals(gameType)) {
            renderChipDropList(tiles, container);
        } else if ("territory".equals(gameType)) {
            renderTerritoryList(tiles, container);
        } else if ("battleship".equals(gameType)) {
            renderBattleshipList(tiles, container);
        } else if ("delve".equals(gameType)) {
            renderDelveList(tiles, board, container);
        } else if ("bounty".equals(gameType)) {
            renderBountyList(tiles, container);
        } else {
            renderDefaultList(tiles, container);
        }
    }

    private void renderTileRaceList(List<BoardTile> tiles, BoardData board, JPanel container) {
        List<BoardTile> current = tiles.stream()
            .filter(t -> t.isCurrent() && t.getSpecial() == null && !t.isCompleted())
            .collect(Collectors.toList());
        if (!current.isEmpty()) {
            container.add(createSectionHeader("\u25B6 Current Tile", COLOR_CURRENT));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : current) {
                container.add(createTileRow(tile, false, "\u25B6 "));
            }
            container.add(Box.createVerticalStrut(8));
        }

        if (board.getOpponents() != null && !board.getOpponents().isEmpty()) {
            container.add(createSectionHeader("\u2716 Opponents", COLOR_OPPONENT));
            container.add(Box.createVerticalStrut(4));
            for (BoardData.OpponentPosition opp : board.getOpponents()) {
                JPanel row = newRow();
                JLabel label = new JLabel("\u2716 " + opp.getName());
                label.setForeground(COLOR_OPPONENT);
                label.setFont(label.getFont().deriveFont(fs(11f)));
                row.add(label, BorderLayout.WEST);
                JLabel posLabel = new JLabel("tile " + opp.getPosition());
                posLabel.setForeground(new Color(150, 150, 180));
                posLabel.setFont(posLabel.getFont().deriveFont(fs(10f)));
                row.add(posLabel, BorderLayout.EAST);
                container.add(row);
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> remaining = tiles.stream()
            .filter(t -> !t.isCompleted() && !t.isCurrent() && t.getSpecial() == null)
            .collect(Collectors.toList());
        if (!remaining.isEmpty()) {
            container.add(createSectionHeader("\u25CB Remaining (" + remaining.size() + ")", COLOR_INCOMPLETE));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : remaining) {
                container.add(createTileRow(tile, false, null));
            }
        }
    }

    private void renderTerritoryList(List<BoardTile> tiles, JPanel container) {
        List<BoardTile> attackable = tiles.stream()
            .filter(t -> t.isAttackable() && !t.isOurs())
            .collect(Collectors.toList());
        if (!attackable.isEmpty()) {
            container.add(createSectionHeader("\u2694 Attackable (" + attackable.size() + ")", COLOR_ATTACKABLE));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : attackable) {
                String name = tile.getTerritoryName() != null ? tile.getTerritoryName() : tile.getName();
                String suffix = tile.getDefenseLevel() > 0 ? " [+" + tile.getDefenseLevel() + " def]" : "";
                JPanel row = newRow();
                JLabel label = new JLabel("\u2694 " + name + suffix);
                label.setForeground(COLOR_ATTACKABLE);
                label.setFont(label.getFont().deriveFont(fs(11f)));
                row.add(label, BorderLayout.WEST);
                JLabel dropLabel = new JLabel(tile.getName());
                dropLabel.setForeground(new Color(150, 150, 180));
                dropLabel.setFont(dropLabel.getFont().deriveFont(fs(9f)));
                row.add(dropLabel, BorderLayout.EAST);
                attachClick(row, tile, ColorScheme.DARKER_GRAY_COLOR);
                container.add(row);
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> ours = tiles.stream().filter(BoardTile::isOurs).collect(Collectors.toList());
        if (!ours.isEmpty()) {
            container.add(createSectionHeader("\u2713 Your Territories (" + ours.size() + ")", COLOR_COMPLETED));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : ours) {
                container.add(createTileRow(tile, true, null));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> locked = tiles.stream()
            .filter(t -> !t.isOurs() && !t.isAttackable())
            .collect(Collectors.toList());
        if (!locked.isEmpty()) {
            container.add(createSectionHeader("\uD83D\uDD12 Out of Reach (" + locked.size() + ")", COLOR_LOCKED));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : locked) {
                container.add(createTileRow(tile, false, null));
            }
        }
    }

    private void renderBattleshipList(List<BoardTile> tiles, JPanel container) {
        List<BoardTile> sunk = tiles.stream()
            .filter(t -> "sunk".equals(t.getAttackResult()))
            .collect(Collectors.toList());
        if (!sunk.isEmpty()) {
            container.add(createSectionHeader("\u2620 Sunk (" + sunk.size() + ")", COLOR_SUNK));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : sunk) {
                container.add(createTileRow(tile, true, "\u2620 "));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> hits = tiles.stream()
            .filter(t -> "hit".equals(t.getAttackResult()))
            .collect(Collectors.toList());
        if (!hits.isEmpty()) {
            container.add(createSectionHeader("\u2716 Hits (" + hits.size() + ")", COLOR_HIT));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : hits) {
                container.add(createTileRow(tile, true, "\u2716 "));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> misses = tiles.stream()
            .filter(t -> "miss".equals(t.getAttackResult()))
            .collect(Collectors.toList());
        if (!misses.isEmpty()) {
            container.add(createSectionHeader("\u25CB Misses (" + misses.size() + ")", COLOR_MISS));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : misses) {
                container.add(createTileRow(tile, false, "\u25CB "));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> remaining = tiles.stream()
            .filter(t -> t.getAttackResult() == null)
            .collect(Collectors.toList());
        if (!remaining.isEmpty()) {
            container.add(createSectionHeader("\u25CB Remaining (" + remaining.size() + ")", COLOR_INCOMPLETE));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : remaining) {
                container.add(createTileRow(tile, false, null));
            }
        }
    }

    private void renderChipDropList(List<BoardTile> tiles, JPanel container) {
        List<BoardTile> available = tiles.stream()
            .filter(t -> t.isAvailable() && !t.isCompleted())
            .collect(Collectors.toList());
        if (!available.isEmpty()) {
            container.add(createSectionHeader("\uD83D\uDD13 Available (" + available.size() + ")", COLOR_AVAILABLE));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : available) {
                container.add(createTileRow(tile, false, null));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> completed = tiles.stream()
            .filter(BoardTile::isCompleted)
            .collect(Collectors.toList());
        if (!completed.isEmpty()) {
            container.add(createSectionHeader("\u2713 Claimed (" + completed.size() + ")", COLOR_COMPLETED));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : completed) {
                container.add(createTileRow(tile, true, null));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> locked = tiles.stream()
            .filter(t -> !t.isAvailable() && !t.isCompleted())
            .collect(Collectors.toList());
        if (!locked.isEmpty()) {
            container.add(createSectionHeader("\uD83D\uDD12 Locked (" + locked.size() + ")", COLOR_LOCKED));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : locked) {
                container.add(createTileRow(tile, false, "\uD83D\uDD12 "));
            }
        }
    }

    private void renderBountyList(List<BoardTile> tiles, JPanel container) {
        List<BoardTile> ordered = orderBounties(tiles);

        List<BoardTile> open = ordered.stream().filter(t -> !t.isCompleted()).collect(Collectors.toList());
        if (!open.isEmpty()) {
            container.add(createSectionHeader("\u25CB Live bounties (" + open.size() + ")", COLOR_BRAND));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : open) {
                container.add(bountyRow(tile));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> claimed = ordered.stream().filter(BoardTile::isCompleted).collect(Collectors.toList());
        if (!claimed.isEmpty()) {
            container.add(createSectionHeader("\u2713 Claimed (" + claimed.size() + ")", COLOR_COMPLETED));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : claimed) {
                container.add(bountyRow(tile));
            }
        }
    }

    /** Payout is the point of a bounty, so it takes the slot a quantity would use. */
    private JPanel bountyRow(BoardTile tile) {
        boolean hot = "hot".equals(tile.getTier());
        JPanel row = newRow();

        JLabel nameLabel = new JLabel((hot ? "\uD83D\uDD25 " : "") + tile.getName());
        nameLabel.setForeground(tile.isCompleted() ? new Color(120, 180, 120) : ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(fs(11f)));
        row.add(nameLabel, BorderLayout.WEST);

        JLabel pointsLabel = new JLabel(tile.getPoints() + " pts");
        pointsLabel.setForeground(hot ? COLOR_HOT : COLOR_MUTED);
        pointsLabel.setFont(pointsLabel.getFont().deriveFont(Font.BOLD, fs(10f)));
        pointsLabel.setToolTipText(bountyStatus(tile));
        row.add(pointsLabel, BorderLayout.EAST);

        attachClick(row, tile, ColorScheme.DARKER_GRAY_COLOR);
        return row;
    }

    private void renderDefaultList(List<BoardTile> tiles, JPanel container) {
        List<BoardTile> completed = tiles.stream()
            .filter(t -> t.isCompleted() && !t.isFree())
            .collect(Collectors.toList());
        if (!completed.isEmpty()) {
            container.add(createSectionHeader("\u2713 Completed (" + completed.size() + ")", COLOR_COMPLETED));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : completed) {
                container.add(createTileRow(tile, true, null));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> remaining = tiles.stream()
            .filter(t -> !t.isCompleted() && !t.isFree())
            .collect(Collectors.toList());
        if (!remaining.isEmpty()) {
            container.add(createSectionHeader("\u25CB Remaining (" + remaining.size() + ")", COLOR_INCOMPLETE));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : remaining) {
                container.add(createTileRow(tile, false, null));
            }
        }
    }

    /**
     * The Delve's list is not a checklist. Its three groups answer three different
     * questions, and conflating them is what made the old panel useless here: the boss
     * table is what you should be killing right now, the objectives are the only drops
     * actually required, and the bonus drops are pure upside that nobody is assigned.
     */
    private void renderDelveList(List<BoardTile> tiles, BoardData board, JPanel container) {
        BoardData.DelveMeta dm = board.getDelveMeta();

        List<BoardData.DelveBoss> bosses = (dm != null && dm.getBosses() != null)
            ? dm.getBosses()
            : java.util.Collections.<BoardData.DelveBoss>emptyList();
        if (!bosses.isEmpty()) {
            container.add(createSectionHeader("\u2694 Kill for supplies (" + bosses.size() + ")", COLOR_BRAND));
            container.add(Box.createVerticalStrut(4));
            for (BoardData.DelveBoss boss : bosses) {
                container.add(createDelveBossRow(boss));
            }
            container.add(Box.createVerticalStrut(8));
        }

        // Guardians and the warden: the only drops the Delve genuinely requires, and only
        // once the party has paid to open that room.
        List<BoardTile> required = tiles.stream()
            .filter(t -> !t.isOptional() && !t.isCompleted())
            .collect(Collectors.toList());
        if (!required.isEmpty()) {
            container.add(createSectionHeader("\uD83D\uDDDD Must drop (" + required.size() + ")", COLOR_CURRENT));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : required) {
                container.add(createTileRow(tile, false, null));
            }
            container.add(Box.createVerticalStrut(8));
        }

        List<BoardTile> bonus = tiles.stream()
            .filter(BoardTile::isOptional)
            .collect(Collectors.toList());
        if (!bonus.isEmpty()) {
            container.add(createSectionHeader("\u2728 Bonus if you get lucky (" + bonus.size() + ")", COLOR_FREE));
            container.add(Box.createVerticalStrut(2));
            container.add(createDelveNote("Not required, not assigned \u2014 the plugin submits these automatically."));
            container.add(Box.createVerticalStrut(4));
            for (BoardTile tile : bonus) {
                container.add(createDelveBonusRow(tile));
            }
        }
    }

    private JPanel createDelveBossRow(BoardData.DelveBoss boss) {
        JPanel row = newRow();

        JLabel nameLabel = new JLabel(boss.getName());
        nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(fs(11f)));
        row.add(nameLabel, BorderLayout.WEST);

        String right = boss.getRate() + "/kill";
        if (boss.getKills() > 0) right = boss.getKills() + " \u00D7 " + right;
        JLabel rateLabel = new JLabel(right);
        rateLabel.setForeground(boss.getKills() > 0 ? COLOR_COMPLETED : tierColor(boss.getTier()));
        rateLabel.setFont(rateLabel.getFont().deriveFont(fs(10f)));
        rateLabel.setToolTipText(boss.getKills() > 0
            ? boss.getKills() + " kills banked, worth " + (boss.getKills() * boss.getRate()) + " supplies"
            : "Worth " + boss.getRate() + " supplies per kill");
        row.add(rateLabel, BorderLayout.EAST);

        return row;
    }

    private JPanel createDelveBonusRow(BoardTile tile) {
        JPanel row = newRow();

        JLabel nameLabel = new JLabel(tile.getName());
        nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(fs(11f)));
        row.add(nameLabel, BorderLayout.WEST);

        if (tile.getTier() != null && !tile.getTier().isEmpty()) {
            JLabel tierLabel = new JLabel(tile.getTier());
            tierLabel.setForeground(new Color(150, 150, 180));
            tierLabel.setFont(tierLabel.getFont().deriveFont(fs(10f)));
            row.add(tierLabel, BorderLayout.EAST);
        }

        return row;
    }

    private JPanel createDelveNote(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(0, 8, 2, 8));
        // Swing has no text wrapping on JLabel; HTML with a width gives us one.
        JLabel label = new JLabel("<html><body style='width:" + Math.max(120, availableWidth - 40) + "px'>" + text + "</body></html>");
        label.setForeground(new Color(130, 130, 150));
        label.setFont(label.getFont().deriveFont(fs(9f)));
        panel.add(label, BorderLayout.WEST);
        return panel;
    }

    private Color tierColor(String tier) {
        if ("hard".equals(tier)) return new Color(226, 120, 120);
        if ("easy".equals(tier)) return new Color(140, 190, 140);
        return new Color(220, 180, 60);
    }

    // ===== UI HELPERS =====

    JPanel createSectionHeader(String text, Color color) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(Font.BOLD, fs(11f)));
        panel.add(label, BorderLayout.WEST);

        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(60, 60, 70));
        panel.add(sep, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel newRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(new EmptyBorder(4, 8, 4, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, detailed ? 40 : 32));
        return row;
    }

    private JPanel createTileRow(BoardTile tile, boolean completed, String prefix) {
        JPanel row = newRow();

        String displayName = (prefix != null ? prefix : "") + tile.getName();
        if (completed && prefix == null) {
            displayName = "\u2713 " + tile.getName();
        }

        JLabel nameLabel = new JLabel(displayName);
        nameLabel.setForeground(completed ? new Color(120, 180, 120) : ColorScheme.LIGHT_GRAY_COLOR);
        nameLabel.setFont(nameLabel.getFont().deriveFont(fs(11f)));
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
            qtyLabel.setFont(qtyLabel.getFont().deriveFont(fs(10f)));
            row.add(qtyLabel, BorderLayout.EAST);
        }

        attachClick(row, tile, ColorScheme.DARKER_GRAY_COLOR);
        return row;
    }

    /**
     * One board cell. Small cells are bare colour swatches because that is all 12px can
     * carry; detailed cells tint the same state colour behind the tile's own name so the
     * pop-out board reads without hovering every square.
     */
    private JComponent createCell(int cellSize, Color state, BoardTile tile,
                                  String label, String progress, String tooltip) {
        JPanel cell = new JPanel();
        cell.setPreferredSize(new Dimension(cellSize, cellSize));
        if (tooltip != null) {
            cell.setToolTipText(tooltip);
        }

        if (!detailed) {
            cell.setBackground(state);
            attachClick(cell, tile, state);
            return cell;
        }

        Color bg = tint(state);
        cell.setLayout(new BorderLayout());
        cell.setBackground(bg);
        cell.setBorder(new CompoundBorder(new LineBorder(state, 1), new EmptyBorder(3, 3, 3, 3)));

        float font = Math.max(9f, Math.min(13f, cellSize / 9f));

        if (label != null && !label.isEmpty()) {
            JLabel name = new JLabel("<html><div style='text-align:center;width:"
                + Math.max(30, cellSize - 14) + "px'>" + escapeHtml(label) + "</div></html>",
                SwingConstants.CENTER);
            name.setForeground(Color.WHITE);
            name.setFont(name.getFont().deriveFont(font));
            cell.add(name, BorderLayout.CENTER);
        }

        if (progress != null && !progress.isEmpty()) {
            JLabel prog = new JLabel(progress, SwingConstants.CENTER);
            prog.setForeground(state);
            prog.setFont(prog.getFont().deriveFont(Font.BOLD, Math.max(9f, font - 1f)));
            cell.add(prog, BorderLayout.SOUTH);
        }

        attachClick(cell, tile, bg);
        return cell;
    }

    private String progressText(BoardTile tile) {
        if (tile == null) return null;
        if (tile.getQuantity() > 1) {
            return tile.getCurrentQty() + "/" + tile.getQuantity();
        }
        return tile.isCompleted() ? "\u2713" : null;
    }

    private void attachClick(JPanel cell, BoardTile tile, Color base) {
        if (tileClickListener == null || tile == null) return;
        cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cell.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                tileClickListener.accept(tile);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                cell.setBackground(base.brighter());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                cell.setBackground(base);
            }
        });
    }

    /** Pulls a state colour most of the way down to the panel background. */
    private static Color tint(Color state) {
        Color base = ColorScheme.DARKER_GRAY_COLOR;
        float f = 0.25f;
        return new Color(
            (int) (base.getRed() + (state.getRed() - base.getRed()) * f),
            (int) (base.getGreen() + (state.getGreen() - base.getGreen()) * f),
            (int) (base.getBlue() + (state.getBlue() - base.getBlue()) * f));
    }

    private JPanel center(JComponent content) {
        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(content);
        return wrapper;
    }

    /** Tile names are player-authored and land inside an HTML label. */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private float fs(float base) {
        return detailed ? base * 1.3f : base;
    }

    static String formatGameType(String gameType) {
        if (gameType == null) return "";
        switch (gameType) {
            case "bingo": return "Bingo";
            case "tilerace": return "Tile Race";
            case "territory": return "Territory War";
            case "chipdrop": return "Chip Drop";
            case "battleship": return "Battleship";
            case "monopoly": return "Boss Tycoon";
            case "delve": return "The Delve";
            case "bounty": return "Bounty Board";
            case "gauntlet": return "The Maze";
            case "slotmachine": return "Slot Machine";
            case "spire": return "Clue Trail";
            default: return gameType;
        }
    }
}
