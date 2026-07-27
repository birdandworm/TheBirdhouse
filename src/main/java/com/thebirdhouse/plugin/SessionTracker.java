package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks player session time and reports to The Birdhouse backend.
 * Sends heartbeats every 5 minutes so the server knows the player is active.
 */
@Slf4j
@Singleton
public class SessionTracker {

    private static final long HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private ActivityTracker activityTracker;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;
    private String currentPlayer;
    private long sessionStart;

    public void startSession(String playerName) {
        if (currentPlayer != null && currentPlayer.equals(playerName)) {
            return; // Already tracking this player
        }

        endSession(); // Clean up any existing session

        currentPlayer = playerName;
        sessionStart = System.currentTimeMillis();

        // Start each session with a clean activity slate so nothing carries over.
        activityTracker.reset();

        apiClient.reportSession(new SessionEvent("login", playerName, sessionStart));

        // Piggyback the leaderboard-activity flush on the session heartbeat so it
        // costs no extra request cadence of its own.
        heartbeatTask = scheduler.scheduleAtFixedRate(
            () -> {
                apiClient.reportSession(new SessionEvent("heartbeat", playerName, System.currentTimeMillis()));
                activityTracker.flush();
            },
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        log.info("Session started for {}", playerName);
    }

    public void endSession() {
        if (currentPlayer == null) return;

        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }

        // Flush any remaining activity before the session closes.
        activityTracker.flush();

        long duration = System.currentTimeMillis() - sessionStart;
        SessionEvent event = new SessionEvent("logout", currentPlayer, System.currentTimeMillis());
        event.setDurationMs(duration);
        apiClient.reportSession(event);

        log.info("Session ended for {} ({}m)", currentPlayer, duration / 60000);
        currentPlayer = null;
    }
}
