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

    @Inject
    private BirdhouseConfig config;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> flushTask;
    private String currentPlayer;
    private long sessionStart;

    /**
     * Notified once the server has actually been told about a session change.
     *
     * The team status panel does not know its own state — it reads presence back from the
     * server. So it cannot be refreshed on the login event: the login write goes out a
     * few ticks later, once the client can say who we are, and asynchronously after that.
     * Refreshing any earlier re-reads exactly the state we are about to change, and the
     * panel then showed us offline until the next poll came round.
     */
    private volatile Runnable onReported = () -> {};

    /** Pushed in by the plugin, which owns the panel this has no business knowing about. */
    public void setSessionReportedListener(Runnable listener) {
        onReported = listener != null ? listener : () -> {};
    }

    private void report(SessionEvent e) {
        Runnable listener = onReported;
        apiClient.reportSession(e).thenRun(listener);
    }

    // Pushed in from the plugin's event handlers rather than read from the client here,
    // because the heartbeat runs on this scheduler thread and reading client state off
    // the client thread is exactly the kind of thing that works until it doesn't.
    private volatile int world;

    /**
     * Stamp the session-wide extras onto every event we send.
     *
     * The world goes out even when sharing is off — the server drops it in that case,
     * and sending zero instead would be a second way to say the same thing.
     */
    private SessionEvent event(String type, String playerName, long timestamp) {
        SessionEvent e = new SessionEvent(type, playerName, timestamp);
        e.setShareStatus(config.showTeamStatus());
        e.setWorld(world);
        return e;
    }

    /**
     * Note the world the player is now on.
     *
     * A hop is reported immediately instead of waiting for the next heartbeat, because
     * a world up to five minutes out of date is worse than none: a teammate hops to
     * meet you and finds you gone. This is the only extra write the feature adds, and
     * it happens a handful of times a session rather than on a timer.
     */
    public void setWorld(int newWorld) {
        if (newWorld <= 0 || newWorld == world) {
            return;
        }
        world = newWorld;
        if (currentPlayer != null && config.showTeamStatus()) {
            reportNow();
        }
    }

    /**
     * Push the current sharing state without waiting for the next heartbeat, so turning
     * the toggle off clears the stored world promptly rather than up to five minutes later.
     */
    public void onStatusSharingChanged() {
        if (currentPlayer != null) {
            reportNow();
        }
    }

    private void reportNow() {
        String name = currentPlayer;
        if (name == null) {
            return;
        }
        scheduler.execute(() -> report(event("heartbeat", name, System.currentTimeMillis())));
    }

    public void startSession(String playerName) {
        if (currentPlayer != null && currentPlayer.equals(playerName)) {
            return; // Already tracking this player
        }

        endSession(); // Clean up any existing session

        currentPlayer = playerName;
        sessionStart = System.currentTimeMillis();

        // Start each session with a clean activity slate so nothing carries over.
        activityTracker.reset();

        report(event("login", playerName, sessionStart));

        // The periodic heartbeat deliberately does not notify: it repeats a state the
        // panel has already read, so waking the poll for it would be a request per beat
        // that can only ever confirm what is on screen.
        heartbeatTask = scheduler.scheduleAtFixedRate(
            () -> apiClient.reportSession(event("heartbeat", playerName, System.currentTimeMillis())),
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
        SessionEvent event = event("logout", currentPlayer, System.currentTimeMillis());
        event.setDurationMs(duration);
        report(event);

        log.info("Session ended for {} ({}m)", currentPlayer, duration / 60000);
        currentPlayer = null;
        // Logging out doesn't put you on world 0, and a stale world must not be
        // re-reported by the next session's login before its first hop.
        world = 0;
    }
}
