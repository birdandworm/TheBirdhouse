package com.thebirdhouse.plugin;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
    private OverlayManager overlayManager;

    @Inject
    private BirdhouseOverlay birdhouseOverlay;

    @Inject
    private BirdhousePanel birdhousePanel;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private SessionTracker sessionTracker;

    private NavigationButton navButton;

    @Override
    protected void startUp() {
        log.info("The Birdhouse plugin started");
        apiClient.setAuthToken(config.authToken());

        // Register overlay
        overlayManager.add(birdhouseOverlay);

        // Register sidebar panel
        BufferedImage icon;
        try {
            icon = ImageUtil.loadImageResource(getClass(), "/panel_icon.png");
        } catch (Exception e) {
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        }
        navButton = NavigationButton.builder()
            .tooltip("The Birdhouse")
            .icon(icon)
            .priority(10)
            .panel(birdhousePanel)
            .build();
        clientToolbar.addNavigation(navButton);

        if (config.trackActivity() && client.getGameState() == GameState.LOGGED_IN) {
            sessionTracker.startSession(client.getLocalPlayer().getName());
        }

        // Load board on startup if already logged in
        if (client.getGameState() == GameState.LOGGED_IN) {
            loadBoard();
        }
    }

    @Override
    protected void shutDown() {
        log.info("The Birdhouse plugin stopped");
        overlayManager.remove(birdhouseOverlay);
        clientToolbar.removeNavigation(navButton);
        sessionTracker.endSession();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            String playerName = client.getLocalPlayer().getName();
            apiClient.setAuthToken(config.authToken());

            loadBoard();

            if (config.trackActivity()) {
                sessionTracker.startSession(playerName);
            }
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            sessionTracker.endSession();
        }
    }

    private void loadBoard() {
        String roomCode = config.roomCode();
        if (roomCode == null || roomCode.isEmpty()) {
            // Try auto-detecting from active rooms
            apiClient.fetchActiveRooms().thenAccept(rooms -> {
                if (rooms != null && !rooms.isEmpty()) {
                    String autoCode = rooms.get(0).getCode();
                    dropMatcher.loadActiveBoard(autoCode);
                    apiClient.fetchBoard(autoCode).thenAccept(board -> {
                        if (board != null) {
                            achievementTracker.setActiveBoard(autoCode, board);
                            birdhousePanel.refreshBoard();
                        }
                    });
                }
            });
        } else {
            dropMatcher.loadActiveBoard(roomCode);
            apiClient.fetchBoard(roomCode).thenAccept(board -> {
                if (board != null) {
                    achievementTracker.setActiveBoard(roomCode, board);
                    birdhousePanel.refreshBoard();
                }
            });
        }
    }

    @Provides
    BirdhouseConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BirdhouseConfig.class);
    }
}
