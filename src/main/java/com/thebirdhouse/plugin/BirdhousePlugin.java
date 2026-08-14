package com.thebirdhouse.plugin;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
    name = "The Birdhouse",
    description = "Auto-submit drop proofs, track activity, and sync with your Birdhouse games",
    tags = {"bingo", "osrs", "birdhouse", "drops", "proof", "clan"}
)
public class BirdhousePlugin extends Plugin {

    @Inject
    private Client client;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private DropMatcher dropMatcher;

    @Inject
    private AchievementTracker achievementTracker;

    @Inject
    private ActivityTracker activityTracker;

    @Inject
    private ClueTracker clueTracker;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BirdhouseOverlay birdhouseOverlay;

    @Inject
    private BirdhousePanel birdhousePanel;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private SessionTracker sessionTracker;

    @Inject
    private EventBus eventBus;

    private NavigationButton navButton;

    @Override
    protected void startUp() {
        log.info("The Birdhouse plugin started");

        eventBus.register(dropMatcher);
        eventBus.register(achievementTracker);
        eventBus.register(activityTracker);
        eventBus.register(clueTracker);

        String token = config.authToken();
        if (token != null) {
            token = token.trim();
        }
        log.info("[Birdhouse] Auth token configured: {} (length={})",
            token != null && !token.isEmpty() ? token.substring(0, Math.min(8, token.length())) + "..." : "(empty)",
            token != null ? token.length() : 0);
        apiClient.setAuthToken(token);

        overlayManager.add(birdhouseOverlay);

        BufferedImage icon;
        try {
            icon = ImageUtil.loadImageResource(getClass(), "/panel_icon.png");
        } catch (Exception e) {
            icon = createFallbackIcon();
        }
        navButton = NavigationButton.builder()
            .tooltip("The Birdhouse")
            .icon(icon)
            .priority(10)
            .panel(birdhousePanel)
            .build();
        clientToolbar.addNavigation(navButton);

        birdhousePanel.startAutoRefresh();

        if (config.trackActivity() && client.getGameState() == GameState.LOGGED_IN) {
            sessionTracker.startSession(client.getLocalPlayer().getName());
        }

        if (client.getGameState() == GameState.LOGGED_IN) {
            loadBoard();
        }
    }

    @Override
    protected void shutDown() {
        log.info("The Birdhouse plugin stopped");
        eventBus.unregister(dropMatcher);
        eventBus.unregister(achievementTracker);
        eventBus.unregister(activityTracker);
        eventBus.unregister(clueTracker);
        overlayManager.remove(birdhouseOverlay);
        clientToolbar.removeNavigation(navButton);
        birdhousePanel.stopAutoRefresh();
        birdhousePanel.shutdown();
        sessionTracker.endSession();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            String playerName = client.getLocalPlayer().getName();
            String token = config.authToken();
            if (token != null) {
                token = token.trim();
            }
            apiClient.setAuthToken(token);
            log.info("[Birdhouse] Login detected for '{}', token present: {}", playerName, token != null && !token.isEmpty());

            // The inventory diff is meaningless across a login, so start from a clean slate.
            clueTracker.reset();
            loadBoard();

            if (config.trackActivity()) {
                sessionTracker.startSession(playerName);
            }
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            sessionTracker.endSession();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"birdhouse".equals(event.getGroup())) {
            return;
        }

        if ("authToken".equals(event.getKey()) || "roomCode".equals(event.getKey())) {
            String token = config.authToken();
            if (token != null) {
                token = token.trim();
            }
            apiClient.setAuthToken(token);
            log.info("[Birdhouse] Config changed ({}), token present: {}", event.getKey(), token != null && !token.isEmpty());

            if (client.getGameState() == GameState.LOGGED_IN) {
                loadBoard();
            }
        }
    }

    private void loadBoard() {
        String rawCode = config.roomCode();
        final String roomCode = (rawCode != null) ? rawCode.trim() : "";

        if (roomCode.isEmpty()) {
            log.info("[Birdhouse] No manual room code set, attempting auto-detect via active-rooms...");
            apiClient.fetchActiveRooms().thenAccept(rooms -> {
                if (rooms != null && !rooms.isEmpty()) {
                    String autoCode = rooms.get(0).getCode();
                    log.info("[Birdhouse] Auto-detected room: {} (from {} active rooms)", autoCode, rooms.size());
                    birdhousePanel.setActiveRoomCode(autoCode);
                    activityTracker.setActiveRoom(autoCode);
                    dropMatcher.loadActiveBoard(autoCode);
                    apiClient.fetchBoard(autoCode).thenAccept(board -> {
                        if (board != null) {
                            achievementTracker.setActiveBoard(autoCode, board);
                            birdhousePanel.refreshBoard();
                        }
                    });
                } else {
                    log.warn("[Birdhouse] No active rooms found. Check: 1) Auth token is set correctly 2) You've joined a room on the website");
                    birdhousePanel.refreshBoard();
                }
            });
        } else {
            log.info("[Birdhouse] Using manual room code: {}", roomCode);
            birdhousePanel.setActiveRoomCode(roomCode);
            activityTracker.setActiveRoom(roomCode);
            dropMatcher.loadActiveBoard(roomCode);
            apiClient.fetchBoard(roomCode).thenAccept(board -> {
                if (board != null) {
                    achievementTracker.setActiveBoard(roomCode, board);
                    birdhousePanel.refreshBoard();
                }
            });
        }
    }

    private BufferedImage createFallbackIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(93, 164, 196));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 11));
        g.drawString("B", 4, 13);
        g.dispose();
        return img;
    }

    @Provides
    BirdhouseConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BirdhouseConfig.class);
    }
}
