package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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

@Slf4j
public class BirdhousePanel extends PluginPanel {

    private static final int PANEL_WIDTH = 225;
    // Poll quickly while a game is live; back off hard when idle or the game has ended
    // so we don't keep hitting the server for dead/stale rooms.
    private static final int POLL_ACTIVE_SECONDS = 60;
    private static final int POLL_IDLE_SECONDS = 300;
    // Chat is polled far faster than the board because a conversation on a 60s delay is
    // not a conversation. It is affordable because the cache host answers it from a live
    // listener, so a poll costs no database read. Bounded either way so a bad config
    // value can't hammer it or make it useless.
    private static final int CHAT_POLL_MIN_SECONDS = 5;
    private static final int CHAT_POLL_MAX_SECONDS = 120;
    // Backed right off when there is nothing to show: no team, chat switched off, or a
    // room that has no thread at all.
    private static final int CHAT_POLL_IDLE_SECONDS = 300;
    private static final int CHAT_PANEL_HEIGHT = 190;

    private final BirdhouseConfig config;
    private final DropMatcher dropMatcher;
    private final BirdhouseApiClient apiClient;
    private final ScreenshotHelper screenshotHelper;
    private final ConfigManager configManager;
    private final Client client;
    private final ClientThread clientThread;
    private final TileIcons tileIcons;
    private final Notifier notifier;

    private final JPanel boardPanel;
    private final JPanel tilesPanel;
    private final JLabel statusLabel;
    private final JLabel progressLabel;
    private final JLabel countdownLabel;
    private final JButton refreshButton;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> autoRefreshTask;
    private ScheduledFuture<?> countdownTask;
    private ScheduledFuture<?> chatTask;
    private Long currentDeadline;

    /**
     * One poller feeds every view of the chat, exactly as the board does, so opening the
     * pop-out window does not double the request rate.
     */
    private final ChatPanel chatPanel;
    private ChatData lastChat;
    /** Newest message id already seen, so a notification fires once and not on first load. */
    private String lastSeenMessageId;
    private boolean chatPrimed;
    private int chatFailures;

    /** The pop-out window mirrors whatever the sidebar last rendered, so that is kept here. */
    private BirdhouseBoardWindow boardWindow;
    private BoardData lastBoard;
    private String lastRoomCode;
    private String lastStatus = "Not connected";
    private String lastProgress = "";
    private Color lastStatusColor = ColorScheme.LIGHT_GRAY_COLOR;

    @Inject
    public BirdhousePanel(BirdhouseConfig config, DropMatcher dropMatcher, BirdhouseApiClient apiClient,
                          ScreenshotHelper screenshotHelper, ConfigManager configManager,
                          Client client, ClientThread clientThread, TileIcons tileIcons,
                          Notifier notifier) {
        super(false);
        this.config = config;
        this.notifier = notifier;
        this.dropMatcher = dropMatcher;
        this.apiClient = apiClient;
        this.screenshotHelper = screenshotHelper;
        this.configManager = configManager;
        this.client = client;
        this.clientThread = clientThread;
        this.tileIcons = tileIcons;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel titleLabel = new JLabel("The Birdhouse");
        titleLabel.setForeground(BoardRenderer.COLOR_BRAND);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        headerButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JButton popOutButton = new JButton("\u2197");
        popOutButton.setToolTipText("Open the board in a resizable window");
        popOutButton.setFocusPainted(false);
        popOutButton.addActionListener(e -> openBoardWindow());
        headerButtons.add(popOutButton);

        refreshButton = new JButton("\u21BB");
        refreshButton.setToolTipText("Refresh board");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> refreshBoard());
        headerButtons.add(refreshButton);

        headerPanel.add(headerButtons, BorderLayout.EAST);

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
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainPanel.add(boardPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Top section
        JPanel topSection = new JPanel();
        topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
        topSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topSection.add(headerPanel);
        topSection.add(statusPanel);

        // Team chat sits under the board rather than replacing it: the point is to read a
        // reply without leaving whatever you were doing, which includes looking at tiles.
        chatPanel = new ChatPanel(this::sendChat, PANEL_WIDTH - 40);
        chatPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        chatPanel.setPreferredSize(new Dimension(PANEL_WIDTH, CHAT_PANEL_HEIGHT));
        chatPanel.setVisible(config.enableTeamChat());

        add(topSection, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);
        add(chatPanel, BorderLayout.SOUTH);
    }

    /** Wired into both chat views; the backend picks the team from the room roster. */
    private void sendChat(String text, java.util.function.Consumer<String> onResult) {
        String roomCode = resolveRoomCode();
        if (roomCode == null) {
            onResult.accept("No active room");
            return;
        }
        apiClient.sendChat(roomCode, text).thenAccept(error -> {
            onResult.accept(error);
            // Pull straight away on success so your own message appears immediately
            // rather than after the next scheduled poll.
            if (error == null) {
                SwingUtilities.invokeLater(this::refreshChat);
            }
        });
    }

    public void startAutoRefresh() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        stopAutoRefresh();
        scheduleNextRefresh(POLL_ACTIVE_SECONDS);
        scheduleNextChatRefresh(0);
        if (config.openBoardWindowOnStart()) {
            SwingUtilities.invokeLater(this::openBoardWindow);
        }
    }

    private int chatPollSeconds() {
        return Math.max(CHAT_POLL_MIN_SECONDS, Math.min(CHAT_POLL_MAX_SECONDS, config.teamChatPollSeconds()));
    }

    private void scheduleNextChatRefresh(int seconds) {
        if (scheduler == null || scheduler.isShutdown()) return;
        if (chatTask != null) {
            chatTask.cancel(false);
        }
        chatTask = scheduler.schedule(
            () -> SwingUtilities.invokeLater(this::refreshChat),
            seconds, TimeUnit.SECONDS
        );
    }

    /** Applies a config change to the chat views without waiting for the next poll. */
    public void onChatConfigChanged() {
        SwingUtilities.invokeLater(() -> {
            boolean enabled = config.enableTeamChat();
            chatPanel.setVisible(enabled);
            if (boardWindow != null && boardWindow.isDisplayable()) {
                boardWindow.setChatVisible(enabled);
            }
            revalidate();
            repaint();
            if (enabled) {
                // Re-priming means switching the feature on does not immediately fire a
                // notification for every message already sitting in the thread.
                chatPrimed = false;
                scheduleNextChatRefresh(0);
            }
        });
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
        if (chatTask != null) {
            chatTask.cancel(false);
            chatTask = null;
        }
    }

    public void shutdown() {
        stopAutoRefresh();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        tileIcons.clear();
        SwingUtilities.invokeLater(() -> {
            if (boardWindow != null) {
                boardWindow.dispose();
                boardWindow = null;
            }
        });
    }

    private String activeRoomCode;

    public void setActiveRoomCode(String code) {
        this.activeRoomCode = code;
    }

    /** Opens (or re-focuses) the resizable board window and hands it the current board. */
    public void openBoardWindow() {
        if (boardWindow == null || !boardWindow.isDisplayable()) {
            boardWindow = new BirdhouseBoardWindow(apiClient, screenshotHelper, config, configManager,
                client, clientThread, tileIcons, this::refreshBoard, this::sendChat);
        }
        boardWindow.setChatVisible(config.enableTeamChat());
        boardWindow.setVisible(true);
        boardWindow.toFront();
        pushToWindow();
    }

    private void pushToWindow() {
        if (boardWindow == null || !boardWindow.isDisplayable()) {
            return;
        }
        if (lastBoard != null) {
            boardWindow.updateBoard(lastBoard, lastRoomCode, lastStatus, lastProgress);
        } else {
            boardWindow.showStatus(lastStatus, lastStatusColor);
        }
        pushChatToWindow();
    }

    /** Shows a single status line with no board, for the states where there is nothing to draw. */
    private void showIdleState(String message, Color color) {
        lastBoard = null;
        lastStatus = message;
        lastProgress = "";
        lastStatusColor = color;

        statusLabel.setText(message);
        statusLabel.setForeground(color);
        progressLabel.setText("");
        countdownLabel.setText("");
        boardPanel.removeAll();
        tilesPanel.removeAll();
        boardPanel.revalidate();
        tilesPanel.revalidate();
        pushToWindow();
    }

    /** The room being tracked: the manually entered code first, then the auto-detected one. */
    private String resolveRoomCode() {
        String roomCode = config.roomCode();
        if (roomCode != null) {
            roomCode = roomCode.trim();
        }
        if ((roomCode == null || roomCode.isEmpty()) && activeRoomCode != null && !activeRoomCode.isEmpty()) {
            roomCode = activeRoomCode;
        }
        return roomCode == null || roomCode.isEmpty() ? null : roomCode;
    }

    /**
     * Poll the caller's team thread.
     *
     * Self-rescheduling like the board poll, and quiet about failures: chat dropping out
     * for one cycle is not worth an error in the panel when the next attempt is seconds
     * away, and the status line is needed for send feedback.
     */
    public void refreshChat() {
        if (!config.enableTeamChat()) {
            scheduleNextChatRefresh(CHAT_POLL_IDLE_SECONDS);
            return;
        }

        String token = config.authToken();
        String roomCode = resolveRoomCode();
        if (token == null || token.trim().isEmpty() || roomCode == null) {
            chatPanel.setUnavailable("Set your auth token and join a room to use team chat.");
            pushChatToWindow();
            scheduleNextChatRefresh(CHAT_POLL_IDLE_SECONDS);
            return;
        }

        apiClient.fetchChat(roomCode).thenAccept(chat -> SwingUtilities.invokeLater(() -> {
            if (chat == null) {
                chatFailures++;
                // One dropped poll is not worth alarming anyone when the next is seconds
                // away, but a persistent failure must not keep looking like a slow load.
                if (chatFailures >= 3) {
                    chatPanel.showProblem("Chat unavailable \u2014 still trying");
                }
                scheduleNextChatRefresh(chatPollSeconds());
                return;
            }
            chatFailures = 0;

            if (chat.getTeamId() == null) {
                chatPanel.setUnavailable("This room has no team chat.");
                lastChat = null;
                pushChatToWindow();
                scheduleNextChatRefresh(CHAT_POLL_IDLE_SECONDS);
                return;
            }

            chatPanel.setSendEnabled(true);
            applyChat(chat);
            scheduleNextChatRefresh(chatPollSeconds());
        }));
    }

    private void applyChat(ChatData chat) {
        lastChat = chat;

        String self = localPlayerName();
        chatPanel.setSelfName(self);
        chatPanel.setThread(chat);
        pushChatToWindow();

        List<ChatMessage> messages = chat.getMessages();
        String newestId = messages == null || messages.isEmpty()
            ? null
            : messages.get(messages.size() - 1).getId();

        // The first successful poll only records where the thread is up to. Without it,
        // logging in would announce the whole backlog as though it had just arrived.
        if (!chatPrimed) {
            chatPrimed = true;
            lastSeenMessageId = newestId;
            return;
        }

        if (newestId == null || newestId.equals(lastSeenMessageId)) {
            return;
        }
        lastSeenMessageId = newestId;

        ChatMessage newest = messages.get(messages.size() - 1);
        boolean mine = self != null && !self.isEmpty()
            && newest.getName() != null && self.equalsIgnoreCase(newest.getName());
        if (!mine && config.notifyOnTeamChat()) {
            notifier.notify(newest.getName() + ": " + summarise(newest));
        }
    }

    /** Notifications are one line, so a long message is cut rather than wrapped. */
    private static String summarise(ChatMessage message) {
        String text = message.getText() != null ? message.getText() : "";
        if (text.isEmpty()) {
            return message.isHasImage() ? "[image]" : "";
        }
        return text.length() <= 80 ? text : text.substring(0, 77) + "\u2026";
    }

    private String localPlayerName() {
        try {
            return client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        } catch (RuntimeException e) {
            // Reading the player off the Swing thread is tolerated everywhere else in this
            // panel, but it is never worth an exception escaping into the poll loop.
            return null;
        }
    }

    private void pushChatToWindow() {
        if (boardWindow == null || !boardWindow.isDisplayable()) {
            return;
        }
        if (lastChat != null) {
            boardWindow.updateChat(lastChat, localPlayerName());
        }
    }

    public void refreshBoard() {
        // Tested ahead of the room code, because a token is what makes any of this work:
        // without one the server rejects every request, so a player who has pasted a room
        // code but not a token would poll indefinitely and only ever see "Failed to load
        // board". This panel is also the sole place that setup gap is visible to them.
        String token = config.authToken();
        if (token == null || token.trim().isEmpty()) {
            showIdleState("\u26A0 No auth token — paste yours in settings", new Color(255, 120, 120));
            scheduleNextRefresh(POLL_IDLE_SECONDS);
            return;
        }

        String roomCode = resolveRoomCode();

        if (roomCode == null) {
            showIdleState("\u26A0 No active room found", ColorScheme.LIGHT_GRAY_COLOR);
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
            showIdleState("Room: " + roomCode + " (no tiles)", ColorScheme.LIGHT_GRAY_COLOR);
            return;
        }

        String gameType = board.getGameType();
        int total = tiles.size();
        int completed = (int) tiles.stream().filter(BoardTile::isCompleted).count();
        int remaining = total - completed;

        boolean ended = Boolean.FALSE.equals(board.getActive());
        lastStatus = "Room: " + roomCode + " \u2022 " + BoardRenderer.formatGameType(gameType) + (ended ? " (ended)" : "");
        lastStatusColor = ColorScheme.LIGHT_GRAY_COLOR;
        statusLabel.setText(lastStatus);

        // For battleship, the meaningful progress is how many of the opponent's ships are
        // still afloat — not how many board tiles are unmarked.
        // The Delve has no checklist to complete — supplies are the currency and sigils are
        // the objective, so a tile count says nothing about how the run is going.
        if ("delve".equals(gameType)) {
            BoardData.DelveMeta dm = board.getDelveMeta();
            if (dm != null) {
                String sigils = dm.getSigilsNeeded() > 0
                    ? "  \u2022  " + dm.getSigilsHeld() + "/" + dm.getSigilsNeeded() + " sigils"
                    : "";
                lastProgress = dm.getSupplies() + " supplies" + sigils;
            } else {
                lastProgress = "Waiting for the Delve to be set up";
            }
        } else if ("battleship".equals(gameType)) {
            BoardData.BattleshipMeta meta = board.getBattleshipMeta();
            if (meta != null && meta.getEnemyShipsTotal() != null && meta.getEnemyShipsTotal() > 0) {
                int shipTotal = meta.getEnemyShipsTotal();
                int shipRemaining = meta.getEnemyShipsRemaining() != null ? meta.getEnemyShipsRemaining() : shipTotal;
                int shipSunk = shipTotal - shipRemaining;
                lastProgress = shipSunk + " / " + shipTotal + " enemy ships sunk (" + shipRemaining + " afloat)";
            } else {
                lastProgress = "Enemy fleet hidden until ships are placed";
            }
        } else {
            lastProgress = completed + " / " + total + " complete (" + remaining + " remaining)";
        }
        progressLabel.setText(lastProgress);

        // Countdown
        currentDeadline = board.getDeadline();
        updateCountdown();
        startCountdownTimer();

        BoardRenderer renderer = new BoardRenderer(PANEL_WIDTH, 0, 18, false);
        renderer.setTileIcons(tileIcons);

        boardPanel.removeAll();
        boardPanel.add(renderer.renderBoard(board), BorderLayout.CENTER);
        boardPanel.revalidate();
        boardPanel.repaint();

        tilesPanel.removeAll();
        renderer.renderTileList(board, tilesPanel);
        tilesPanel.revalidate();
        tilesPanel.repaint();

        lastBoard = board;
        lastRoomCode = roomCode;
        pushToWindow();
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
            setCountdown("", countdownLabel.getForeground());
            return;
        }
        long remaining = currentDeadline - System.currentTimeMillis();
        if (remaining <= 0) {
            setCountdown("\u23F0 Event ended!", new Color(255, 100, 100));
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
        setCountdown("\u23F0 " + timeStr + " remaining",
            remaining < 3600000 ? new Color(255, 100, 100) : new Color(255, 180, 80));
    }

    private void setCountdown(String text, Color color) {
        countdownLabel.setText(text);
        countdownLabel.setForeground(color);
        if (boardWindow != null && boardWindow.isDisplayable()) {
            boardWindow.setCountdown(text, color);
        }
    }
}
