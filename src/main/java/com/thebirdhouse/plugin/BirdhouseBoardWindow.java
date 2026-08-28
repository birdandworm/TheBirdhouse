package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.ThinProgressBar;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The board at a size you can actually play from.
 *
 * The sidebar is a hard 225px — {@code PluginPanel.PANEL_WIDTH} is a constant in
 * RuneLite core and no plugin can widen it — so a 7x7 board can only ever be a strip of
 * coloured pixels there. This is the same board data in a window the player sizes
 * themselves, with each tile clickable so a proof can be captured and submitted without
 * leaving the client.
 */
@Slf4j
class BirdhouseBoardWindow extends JFrame {

    private static final String CONFIG_GROUP = "birdhouse";
    private static final String BOUNDS_KEY = "boardWindowBounds";
    private static final String PINNED_KEY = "boardWindowAlwaysOnTop";

    private static final int DEFAULT_WIDTH = 940;
    private static final int DEFAULT_HEIGHT = 680;
    private static final int LIST_WIDTH = 280;
    private static final int MAX_CELL_SIZE = 150;
    /** Re-laying out on every pixel of a drag is wasteful and flickers. */
    private static final int RESIZE_DEBOUNCE_MS = 200;
    private static final int CAPTURE_TIMEOUT_MS = 6000;
    /**
     * Matches the backend's own de-dupe window. Every submission is a function invocation
     * plus a screenshot written to storage, so an impatient player clicking the same tile
     * repeatedly is stopped here rather than paid for and then discarded server-side.
     */
    private static final long RESUBMIT_COOLDOWN_MS = 30_000L;

    private static final String SITE_URL = "https://thebirdhouse.games";

    private static final Color COLOR_ERROR = new Color(255, 120, 120);
    private static final Color COLOR_OK = new Color(120, 200, 120);
    private static final Color COLOR_MUTED = new Color(150, 150, 170);

    private final BirdhouseApiClient apiClient;
    private final ScreenshotHelper screenshotHelper;
    private final BirdhouseConfig config;
    private final ConfigManager configManager;
    private final Client client;
    private final ClientThread clientThread;
    private final TileIcons tileIcons;
    private final Runnable refreshRequest;

    private final JLabel statusLabel = new JLabel("Waiting for a board\u2026");
    private final JLabel progressLabel = new JLabel("");
    private final JLabel countdownLabel = new JLabel("");
    private final JButton pinButton = new JButton();
    private final ThinProgressBar overallBar = new ThinProgressBar();
    private final JPanel boardHolder = new JPanel(new BorderLayout());
    private final JPanel listHolder = new JPanel();
    private final JScrollPane boardScroll;
    private final Timer resizeDebounce;

    private BoardData board;
    private String roomCode;
    private int lastRenderWidth;
    private int lastRenderHeight;
    private final Map<String, Long> lastSubmittedAt = new HashMap<>();

    BirdhouseBoardWindow(BirdhouseApiClient apiClient, ScreenshotHelper screenshotHelper,
                         BirdhouseConfig config, ConfigManager configManager, Client client,
                         ClientThread clientThread, TileIcons tileIcons, Runnable refreshRequest) {
        this.apiClient = apiClient;
        this.screenshotHelper = screenshotHelper;
        this.config = config;
        this.configManager = configManager;
        this.client = client;
        this.clientThread = clientThread;
        this.tileIcons = tileIcons;
        this.refreshRequest = refreshRequest;

        setTitle("The Birdhouse \u2014 Board");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(520, 400));
        applyIcon();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(ColorScheme.DARK_GRAY_COLOR);

        root.add(buildHeader(), BorderLayout.NORTH);

        boardHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);
        boardScroll = new JScrollPane(boardHolder);
        boardScroll.setBorder(null);
        boardScroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        boardScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(boardScroll, BorderLayout.CENTER);

        listHolder.setLayout(new BoxLayout(listHolder, BoxLayout.Y_AXIS));
        listHolder.setBackground(ColorScheme.DARK_GRAY_COLOR);
        listHolder.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane listScroll = new JScrollPane(listHolder);
        listScroll.setBorder(null);
        listScroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setPreferredSize(new Dimension(LIST_WIDTH, 0));
        root.add(listScroll, BorderLayout.EAST);

        JLabel hint = new JLabel("Click any tile to capture a screenshot and submit it as proof.");
        hint.setForeground(COLOR_MUTED);
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setBorder(new EmptyBorder(4, 10, 6, 10));
        root.add(hint, BorderLayout.SOUTH);

        setContentPane(root);

        resizeDebounce = new Timer(RESIZE_DEBOUNCE_MS, e -> rebuildIfResized());
        resizeDebounce.setRepeats(false);
        boardScroll.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeDebounce.restart();
            }
        });

        restoreBounds();
        setPinned(config.boardWindowAlwaysOnTop());
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setBorder(new EmptyBorder(10, 10, 8, 10));

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("The Birdhouse");
        title.setForeground(BoardRenderer.COLOR_BRAND);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);

        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(statusLabel);

        progressLabel.setForeground(new Color(150, 200, 100));
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.BOLD, 13f));
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(progressLabel);

        overallBar.setForeground(new Color(150, 200, 100));
        overallBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        overallBar.setMaximumSize(new Dimension(260, 6));
        overallBar.setPreferredSize(new Dimension(260, 6));
        text.add(Box.createVerticalStrut(4));
        text.add(overallBar);

        header.add(text, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);

        countdownLabel.setForeground(new Color(255, 180, 80));
        countdownLabel.setFont(countdownLabel.getFont().deriveFont(12f));
        controls.add(countdownLabel);

        JButton siteButton = new JButton("Standings \u2197");
        siteButton.setToolTipText("Open this event's full standings on thebirdhouse.games");
        siteButton.setFocusPainted(false);
        siteButton.addActionListener(e -> openOnSite());
        controls.add(siteButton);

        pinButton.setFocusPainted(false);
        pinButton.addActionListener(e -> setPinned(!isAlwaysOnTop()));
        controls.add(pinButton);

        JButton refreshButton = new JButton("\u21BB");
        refreshButton.setToolTipText("Refresh board");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> refreshRequest.run());
        controls.add(refreshButton);

        header.add(controls, BorderLayout.EAST);
        return header;
    }

    /**
     * The window deliberately shows only this player's own board. Everything wider than
     * that — other teams, standings, the proof gallery, setup — lives on the site, and
     * this is the one click that gets there.
     */
    private void openOnSite() {
        if (roomCode == null || roomCode.isEmpty()) {
            LinkBrowser.browse(SITE_URL);
            return;
        }
        LinkBrowser.browse(SITE_URL + "/#/leaderboard/" + roomCode);
    }

    private void applyIcon() {
        try {
            BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/panel_icon.png");
            if (icon != null) {
                setIconImage(icon);
            }
        } catch (Exception e) {
            log.debug("[Birdhouse] No window icon available: {}", e.getMessage());
        }
    }

    // ===== STATE =====

    void updateBoard(BoardData board, String roomCode, String status, String progress) {
        this.board = board;
        this.roomCode = roomCode;
        statusLabel.setText(status);
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        progressLabel.setText(progress);
        updateOverallBar(board);
        rebuild();
    }

    /**
     * Free squares are excluded: a bingo board that hands you the middle tile would
     * otherwise open at 4% done and never reach zero remaining.
     */
    private void updateOverallBar(BoardData board) {
        int total = 0;
        int done = 0;
        if (board != null && board.getTiles() != null) {
            for (BoardTile tile : board.getTiles()) {
                if (tile.isFree()) {
                    continue;
                }
                total++;
                if (tile.isCompleted()) {
                    done++;
                }
            }
        }
        overallBar.setMaximumValue(Math.max(1, total));
        overallBar.setValue(done);
        overallBar.setVisible(total > 0);
    }

    void showStatus(String message, Color color) {
        this.board = null;
        statusLabel.setText(message);
        statusLabel.setForeground(color);
        progressLabel.setText("");
        countdownLabel.setText("");
        overallBar.setVisible(false);
        rebuild();
    }

    void setCountdown(String text, Color color) {
        countdownLabel.setText(text);
        countdownLabel.setForeground(color);
    }

    private void rebuildIfResized() {
        Dimension extent = boardScroll.getViewport().getExtentSize();
        if (Math.abs(extent.width - lastRenderWidth) < 8 && Math.abs(extent.height - lastRenderHeight) < 8) {
            return;
        }
        rebuild();
    }

    private void rebuild() {
        boardHolder.removeAll();
        listHolder.removeAll();

        if (board != null) {
            Dimension extent = boardScroll.getViewport().getExtentSize();
            lastRenderWidth = extent.width > 60 ? extent.width : DEFAULT_WIDTH - LIST_WIDTH;
            lastRenderHeight = extent.height > 60 ? extent.height : DEFAULT_HEIGHT - 120;

            BoardRenderer boardRenderer = new BoardRenderer(lastRenderWidth, lastRenderHeight, MAX_CELL_SIZE, true);
            boardRenderer.setTileClickListener(this::showTileDialog);
            boardRenderer.setTileIcons(tileIcons);
            boardHolder.add(boardRenderer.renderBoard(board), BorderLayout.CENTER);

            BoardRenderer listRenderer = new BoardRenderer(LIST_WIDTH, 0, 18, false);
            listRenderer.setTileClickListener(this::showTileDialog);
            listRenderer.setTileIcons(tileIcons);
            listRenderer.renderTileList(board, listHolder);
        }

        boardHolder.revalidate();
        boardHolder.repaint();
        listHolder.revalidate();
        listHolder.repaint();
    }

    // ===== SUBMISSION =====

    private void showTileDialog(BoardTile tile) {
        if (roomCode == null || roomCode.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No active room \u2014 load a board first.",
                "The Birdhouse", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(this, "Submit proof", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(14, 14, 14, 14));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        title.setBackground(ColorScheme.DARK_GRAY_COLOR);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel tileIcon = new JLabel();
        if (tileIcons.apply(tile, tileIcon)) {
            title.add(tileIcon);
        }
        title.add(heading(tile.getName()));
        content.add(title);

        // The host's note is a condition on the claim, so it goes above the controls in
        // its own colour rather than being folded into the grey descriptive line.
        String note = tile.getDescription();
        if (note != null && !note.trim().isEmpty()) {
            content.add(Box.createVerticalStrut(6));
            JLabel noteLabel = body("<html><body style='width:340px'>" + note.trim() + "</body></html>",
                new Color(255, 200, 100), 12f);
            content.add(noteLabel);
        }

        content.add(Box.createVerticalStrut(6));
        content.add(body(describe(tile), ColorScheme.LIGHT_GRAY_COLOR, 12f));
        content.add(Box.createVerticalStrut(12));

        content.add(body("Submitting as", COLOR_MUTED, 11f));
        List<String> options = claimOptions(tile);
        JComboBox<String> itemBox = new JComboBox<>(options.toArray(new String[0]));
        // Editable because "any drop from this boss" tiles list the NPC, not the item the
        // player actually got, and the proof reads better with the real drop name on it.
        itemBox.setEditable(true);
        itemBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        itemBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, itemBox.getPreferredSize().height));
        content.add(itemBox);

        JSpinner qtySpinner = null;
        if (tile.getQuantity() > 1) {
            int remaining = Math.max(1, tile.getQuantity() - tile.getCurrentQty());
            content.add(Box.createVerticalStrut(8));
            content.add(body("Quantity", COLOR_MUTED, 11f));
            qtySpinner = new JSpinner(new SpinnerNumberModel(1, 1, remaining, 1));
            qtySpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
            qtySpinner.setMaximumSize(new Dimension(120, qtySpinner.getPreferredSize().height));
            content.add(qtySpinner);
        }

        content.add(Box.createVerticalStrut(12));
        JLabel feedback = body(" ", COLOR_MUTED, 11f);
        content.add(feedback);
        content.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose());
        buttons.add(cancel);

        JButton capture = new JButton("Capture screenshot");
        buttons.add(capture);
        content.add(buttons);

        content.add(Box.createVerticalStrut(6));
        content.add(body("Manual submissions go to your event admins for review.", COLOR_MUTED, 10f));

        if (tile.isCompleted() && !tile.isRepeatable()) {
            capture.setEnabled(false);
            feedback.setText("This tile is already complete.");
        } else if (onCooldown(tile)) {
            capture.setEnabled(false);
            feedback.setText("Just submitted \u2014 wait for the board to refresh.");
        }

        final JSpinner spinner = qtySpinner;
        capture.addActionListener(e -> {
            String item = itemBox.getEditor().getItem() == null ? "" : itemBox.getEditor().getItem().toString().trim();
            if (item.isEmpty()) {
                feedback.setText("Name the item you're claiming.");
                feedback.setForeground(COLOR_ERROR);
                return;
            }
            int qty = spinner != null ? (Integer) spinner.getValue() : 1;
            capture.setEnabled(false);
            feedback.setForeground(COLOR_MUTED);
            feedback.setText("Capturing the game window\u2026");
            captureThenPreview(dialog, tile, item, qty, feedback, capture);
        });

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setSize(Math.max(380, dialog.getWidth()), dialog.getHeight());
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void captureThenPreview(JDialog owner, BoardTile tile, String item, int qty,
                                    JLabel feedback, JButton capture) {
        // DrawManager hands the frame back on the client thread and never fires at all if
        // the client isn't rendering (logged out, minimised), so the button has to be
        // released on a timer rather than waiting forever.
        AtomicBoolean settled = new AtomicBoolean();
        Timer timeout = new Timer(CAPTURE_TIMEOUT_MS, e -> {
            if (settled.compareAndSet(false, true)) {
                feedback.setForeground(COLOR_ERROR);
                feedback.setText("Couldn't capture the game \u2014 is the client rendering?");
                capture.setEnabled(true);
            }
        });
        timeout.setRepeats(false);
        timeout.start();

        screenshotHelper.captureAsync(bytes -> SwingUtilities.invokeLater(() -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            timeout.stop();
            if (bytes == null || bytes.length == 0) {
                feedback.setForeground(COLOR_ERROR);
                feedback.setText("Screenshot failed. Try again.");
                capture.setEnabled(true);
                return;
            }
            feedback.setForeground(COLOR_MUTED);
            feedback.setText(" ");
            capture.setEnabled(true);
            showPreview(owner, tile, item, qty, bytes, feedback, capture);
        }));
    }

    private void showPreview(JDialog owner, BoardTile tile, String item, int qty, byte[] bytes,
                             JLabel ownerFeedback, JButton ownerCapture) {
        JDialog preview = new JDialog(owner, "Review proof", true);
        preview.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);

        content.add(body(tile.getName() + "  \u2014  " + item + (qty > 1 ? "  \u00D7" + qty : ""),
            ColorScheme.LIGHT_GRAY_COLOR, 12f), BorderLayout.NORTH);

        JLabel image = new JLabel(scaledIcon(bytes), SwingConstants.CENTER);
        image.setBorder(new EmptyBorder(4, 4, 4, 4));
        content.add(image, BorderLayout.CENTER);

        JLabel status = body(" ", COLOR_MUTED, 11f);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> preview.dispose());
        buttons.add(cancel);

        JButton retake = new JButton("Retake");
        buttons.add(retake);

        JButton submit = new JButton("Submit");
        buttons.add(submit);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(ColorScheme.DARK_GRAY_COLOR);
        south.add(status, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);

        retake.addActionListener(e -> {
            preview.dispose();
            ownerCapture.setEnabled(false);
            ownerFeedback.setForeground(COLOR_MUTED);
            ownerFeedback.setText("Capturing the game window\u2026");
            captureThenPreview(owner, tile, item, qty, ownerFeedback, ownerCapture);
        });

        submit.addActionListener(e -> {
            submit.setEnabled(false);
            retake.setEnabled(false);
            status.setForeground(COLOR_MUTED);
            status.setText("Submitting\u2026");
            submitProof(preview, owner, tile, item, qty, bytes, status, submit, retake);
        });

        preview.setContentPane(content);
        preview.pack();
        preview.setLocationRelativeTo(owner);
        preview.setVisible(true);
    }

    private void submitProof(JDialog preview, JDialog owner, BoardTile tile, String item, int qty,
                             byte[] bytes, JLabel status, JButton submit, JButton retake) {
        final String room = roomCode;
        final String gameType = tile.getGameType() != null ? tile.getGameType()
            : (board != null ? board.getGameType() : null);

        // getLocalPlayer() belongs to the client thread, so the name is resolved there and
        // the request is fired from the callback rather than off the EDT.
        clientThread.invoke(() -> {
            Player local = client.getLocalPlayer();

            ProofPayload payload = new ProofPayload();
            payload.setRoomCode(room);
            payload.setPlayerName(local != null ? local.getName() : null);
            payload.setTileKey(tile.getKey());
            payload.setTileName(tile.getName());
            payload.setTerritoryName(tile.getTerritoryName());
            payload.setGameType(gameType);
            payload.setItemName(item);
            payload.setQuantity(Math.max(1, qty));
            payload.setTimestamp(System.currentTimeMillis());
            payload.setManual(true);

            apiClient.submitProof(payload, bytes).thenAccept(result ->
                SwingUtilities.invokeLater(() -> {
                    if (result.isOk()) {
                        markSubmitted(tile);
                        preview.dispose();
                        owner.dispose();
                        notifyInGame(result.isDuplicate()
                            ? "[Birdhouse] Already submitted: " + tile.getName()
                            : "[Birdhouse] Proof sent for review: " + tile.getName() + " (" + item + ")");
                        refreshRequest.run();
                        return;
                    }
                    status.setForeground(COLOR_ERROR);
                    status.setText(result.getMessage() != null ? result.getMessage() : "Submission failed");
                    submit.setEnabled(true);
                    retake.setEnabled(true);
                }));
        });
    }

    private boolean onCooldown(BoardTile tile) {
        if (tile.getKey() == null) {
            return false;
        }
        Long last = lastSubmittedAt.get(tile.getKey());
        return last != null && System.currentTimeMillis() - last < RESUBMIT_COOLDOWN_MS;
    }

    private void markSubmitted(BoardTile tile) {
        if (tile.getKey() != null) {
            lastSubmittedAt.put(tile.getKey(), System.currentTimeMillis());
        }
    }

    private void notifyInGame(String message) {
        if (!config.notifyOnSubmit()) {
            return;
        }
        clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, ""));
    }

    /**
     * Item names the backend will accept for this tile. It validates a submission against
     * the tile's own match rule, so a free-text guess is usually rejected — these come
     * straight off the /board payload that produced the rule.
     */
    private List<String> claimOptions(BoardTile tile) {
        Set<String> options = new LinkedHashSet<>();
        addAll(options, tile.getMatchItems());
        addAll(options, tile.getRequiredItems());
        if (tile.getMatchGroups() != null) {
            for (BoardTile.MatchGroup group : tile.getMatchGroups()) {
                addAll(options, group.getItems());
            }
        }
        if (options.isEmpty() && tile.getMatchName() != null && !tile.getMatchName().isEmpty()) {
            options.add(tile.getMatchName());
        }
        if (options.isEmpty()) {
            options.add(tile.getName());
        }
        return new ArrayList<>(options);
    }

    private static void addAll(Set<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String value : source) {
            if (value != null && !value.trim().isEmpty()) {
                target.add(value.trim());
            }
        }
    }

    private String describe(BoardTile tile) {
        StringBuilder sb = new StringBuilder("<html><body style='width:340px'>");
        if (tile.getQuantity() > 1) {
            sb.append(tile.getCurrentQty()).append(" of ").append(tile.getQuantity()).append(" collected");
        } else {
            sb.append(tile.isCompleted() ? "Complete" : "Not yet complete");
        }
        if (tile.isRepeatable()) {
            sb.append(" \u2022 repeatable");
        }
        List<String> options = claimOptions(tile);
        sb.append("<br>Counts for: ").append(String.join(", ", options));
        sb.append("</body></html>");
        return sb.toString();
    }

    private ImageIcon scaledIcon(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return new ImageIcon();
            }
            double scale = Math.min(1.0, Math.min(720.0 / image.getWidth(), 460.0 / image.getHeight()));
            int w = (int) (image.getWidth() * scale);
            int h = (int) (image.getHeight() * scale);
            return new ImageIcon(image.getScaledInstance(w, h, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            log.warn("[Birdhouse] Couldn't render screenshot preview", e);
            return new ImageIcon();
        }
    }

    private JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(BoardRenderer.COLOR_BRAND);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JLabel body(String text, Color color, float size) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(size));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    // ===== WINDOW STATE =====

    private void setPinned(boolean pinned) {
        setAlwaysOnTop(pinned);
        pinButton.setText(pinned ? "Unpin" : "Pin");
        pinButton.setToolTipText(pinned
            ? "Stop keeping this window above others"
            : "Keep this window above other windows");
        configManager.setConfiguration(CONFIG_GROUP, PINNED_KEY, pinned);
    }

    private void restoreBounds() {
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        setLocationRelativeTo(null);

        String raw = configManager.getConfiguration(CONFIG_GROUP, BOUNDS_KEY);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            String[] parts = raw.split(",");
            if (parts.length != 4) {
                return;
            }
            Rectangle saved = new Rectangle(
                Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
            // A monitor that has since been unplugged would put the window somewhere the
            // player can't reach it, so an off-screen restore falls back to the default.
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (saved.width >= 400 && saved.height >= 300 && screen.intersects(saved)) {
                setBounds(saved);
            }
        } catch (RuntimeException e) {
            log.debug("[Birdhouse] Ignoring unreadable saved window bounds '{}'", raw);
        }
    }

    private void saveBounds() {
        Rectangle b = getBounds();
        if (b.width <= 0 || b.height <= 0) {
            return;
        }
        configManager.setConfiguration(CONFIG_GROUP, BOUNDS_KEY,
            b.x + "," + b.y + "," + b.width + "," + b.height);
    }

    @Override
    public void dispose() {
        resizeDebounce.stop();
        saveBounds();
        super.dispose();
    }
}
