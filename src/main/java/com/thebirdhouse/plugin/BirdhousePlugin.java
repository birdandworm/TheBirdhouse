package com.thebirdhouse.plugin;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

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
    private OverlayManager overlayManager;

    @Inject
    private SessionTracker sessionTracker;

    @Override
    protected void startUp() {
        log.info("The Birdhouse plugin started");
        apiClient.setAuthToken(config.authToken());

        if (config.trackActivity() && client.getGameState() == GameState.LOGGED_IN) {
            sessionTracker.startSession(client.getLocalPlayer().getName());
        }
    }

    @Override
    protected void shutDown() {
        log.info("The Birdhouse plugin stopped");
        sessionTracker.endSession();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            String playerName = client.getLocalPlayer().getName();
            apiClient.setAuthToken(config.authToken());
            dropMatcher.loadActiveBoard(config.roomCode());

            if (config.trackActivity()) {
                sessionTracker.startSession(playerName);
            }
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            sessionTracker.endSession();
        }
    }

    @Provides
    BirdhouseConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BirdhouseConfig.class);
    }
}
