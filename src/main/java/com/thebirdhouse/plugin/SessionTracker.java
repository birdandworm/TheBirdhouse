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
 * Sends heartbeats every 5 minutes so the server knows the player is active, and
 * flushes accumulated activity on its own faster cadence.
 */
@Slf4j
@Singleton
public class SessionTracker {

    private static final long HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    // The activity flush used to ride the heartbeat, which meant kill counts only
    // reached the board in five-minute steps. That is invisible on a leaderboard but
    // very visible in The Delve, where kills ARE the currency: you kill a boss and the
    // supply counter sits still long enough to look broken.
    //
    // It gets its own faster cadence because a flush is now cheap. flush() returns
    // early when nothing has accumulated, so an idle client still sends nothing, and
    // the server reads only the flushing player's roster entry rather than the whole
    // clan's — so the cost of a flush no longer scales with event size.
    private static final long FLUSH_INTERVAL_MS = 60 * 1000; // 1 minute

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private ActivityTracker activityTracker;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> flushTask;
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

        heartbeatTask = scheduler.scheduleAtFixedRate(
            () -> apiClient.reportSession(new SessionEvent("heartbeat", playerName, System.currentTimeMillis())),
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        flushTask = scheduler.scheduleAtFixedRate(
            activityTracker::flush,
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
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
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
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
