package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reports where the player is standing during a round of The Impostor.
 *
 * The mode used to run on an abstract map: you picked a room from a dropdown and the game
 * took your word for it. This replaces that with the real one, so "where were you" is a
 * question about where the player actually stood. Everything downstream — which named
 * place that is, how long they were there, who could therefore have seen them — is decided
 * by the server. This class knows only how to read a coordinate and when to send it.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. It does not know what a zone is, does not filter
 * positions by whether they look interesting, and never reads anybody else's. Those are
 * all server-side policy, which can be redeployed in a minute; anything moved in here
 * could only change as fast as the Plugin Hub reviews a release, and the zone boundaries
 * this feature rests on have never been checked by a person standing in them.
 *
 * WHY THERE ARE TWO THREADS. The position is read on the client thread, in
 * {@link #onGameTick}, and stashed in a volatile field for the scheduler to pick up — the
 * same split {@link SessionTracker} uses for the world number, and for the same reason:
 * reading client state off the client thread is the kind of thing that works until it
 * doesn't. The scheduler never touches the client.
 */
@Slf4j
@Singleton
public class ImpostorPositionTracker {

    /**
     * How often the send decision is considered. Deliberately faster than the send cadence
     * itself, which the server dictates and can change at runtime — a fixed-rate task at
     * the send interval would have to be cancelled and rebuilt every time that happened.
     */
    private static final long POLL_INTERVAL_MS = 1000;

    /** Used until the server states otherwise, which it does on the first accepted send. */
    private static final long DEFAULT_TICK_MS = 10_000;
    private static final long DEFAULT_KEEPALIVE_MS = 60_000;

    /**
     * Consecutive failures before this gives up until the round changes.
     *
     * Failures here are nearly unreachable — the gate below needs a board that only a
     * working token could have fetched — but "nearly" plus a timer is how a client ends up
     * posting into the void for a whole evening.
     */
    private static final int MAX_FAILURES = 3;

    @Inject
    private Client client;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private BirdhouseApiClient apiClient;

    /** Holds the board the panel polls, which is where the game type and phase come from. */
    @Inject
    private DropMatcher dropMatcher;

    /** For the chat line, which has to be written on the client thread or it is dropped. */
    @Inject
    private ClientThread clientThread;

    /** For the flash, respecting whatever the player already chose about notifications. */
    @Inject
    private Notifier notifier;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> task;

    /** Written on the client thread, read on the scheduler thread. Null means "do not send". */
    private volatile Sample latest;

    // Scheduler-thread state. Not volatile: nothing else touches it.
    private Sample lastSent;
    private long lastSentAt;
    private String lastKey;
    private int failures;

    // Written from HTTP callbacks, read on the scheduler thread.
    private volatile long tickMs = DEFAULT_TICK_MS;
    private volatile long keepaliveMs = DEFAULT_KEEPALIVE_MS;
    private volatile String stoppedKey;

    /** The last zone the server named, purely so a log line can say something useful. */
    private volatile String zone;

    /**
     * Told they are out. Volatile because the HTTP callback sets it and the client thread
     * reads it every frame to draw the notice.
     */
    private volatile boolean dead;

    public void start() {
        stop();
        task = scheduler.scheduleAtFixedRate(this::poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        latest = null;
        lastSent = null;
        lastKey = null;
        stoppedKey = null;
        failures = 0;
        zone = null;
        dead = false;
        role = null;
        meeting = false;
        blackout = false;
        bodies = java.util.Collections.emptyList();
    }

    /**
     * Forget everything about where we were and what the server last said, without tearing
     * down the scheduler.
     *
     * Needed on logout because {@link #onGameTick} is the only thing that clears a stale
     * sample, and it stops firing the moment the player leaves. Left alone, the last
     * position of the evening would keep being resent on the keepalive — reporting somebody
     * as standing in the sewers long after they closed the client, which is the false alibi
     * the server's absence handling exists to prevent.
     *
     * The stop flag goes with it, which matters when the player has just switched the
     * feature on: a refusal from an earlier round would otherwise keep them silent until
     * the next phase change, and the cost of being wrong is one request that gets refused
     * again.
     */
    public void clearPosition() {
        latest = null;
        lastSent = null;
        stoppedKey = null;
        failures = 0;
        zone = null;
        // Cleared so a relog re-hears it. The server re-asserts a death, so this costs one
        // repeated notification to somebody who is dead and ought to be reminded of it —
        // better than a ghost who logged back in and found the notice gone.
        dead = false;
        role = null;
        meeting = false;
        blackout = false;
        bodies = java.util.Collections.emptyList();
    }

    /**
     * Own role, in-zone bodies, meeting and blackout — written from the HTTP callback,
     * read on the client thread by overlays and menus.
     */
    private volatile String role;
    private volatile boolean meeting;
    private volatile boolean blackout;
    private volatile java.util.List<ImpostorBody> bodies = java.util.Collections.emptyList();

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!config.shareImpostorPosition() || !shouldSample(dropMatcher.getActiveBoard())) {
            latest = null;
            return;
        }
        if (client.getGameState() != GameState.LOGGED_IN) {
            latest = null;
            return;
        }
        Player me = client.getLocalPlayer();
        WorldPoint where = me != null ? me.getWorldLocation() : null;
        if (where == null) {
            latest = null;
            return;
        }
        // Reported exactly as the client gives it, instance flag included and coordinates
        // unresolved. See PositionPayload#instance for why resolving them would be wasted
        // work rather than diligence.
        latest = new Sample(where.getX(), where.getY(), where.getPlane(),
            client.getTopLevelWorldView().isInstance());
    }

    /**
     * Whether the plugin should be reporting position for this board at all.
     *
     * A phase of anything but {@code round} means no: the lobby, a meeting, the vote and
     * the reveal all want people talking rather than being measured. Older servers send no
     * phase at all, which reads as "no round" and switches the feature off rather than on,
     * because a plugin that reported through a game the server was not expecting it in
     * would be collecting positions nobody asked for.
     */
    static boolean roundIsRunning(BoardData board) {
        return board != null
            && "impostor".equals(board.getGameType())
            && "round".equals(board.getPhase());
    }

    /**
     * Whether to send coordinates, not just a status ping.
     *
     * The lobby is included because deal refuses anyone without a fresh sample. Meetings
     * still go out as status-only — people are arguing, not being measured.
     */
    static boolean shouldSample(BoardData board) {
        if (board == null || !"impostor".equals(board.getGameType())) {
            return false;
        }
        String phase = board.getPhase();
        return phase == null || phase.isEmpty() || "lobby".equals(phase) || "round".equals(phase);
    }

    /**
     * Whether to keep talking to the server at all, which is a weaker question than whether a
     * round is running, and has to be.
     *
     * A kill lands when the round closes — the same instant the phase stops being
     * {@code round}. Gate the sending on {@link #roundIsRunning} and the plugin falls silent
     * at precisely the moment the server has something to tell it, so the victim finds out
     * they are dead at the start of the *next* round: after the meeting they most needed to
     * keep quiet through. Today it happens to work, because the board this reads is up to a
     * minute stale and the plugin therefore keeps ticking through a round that has already
     * ended. Relying on a cache being out of date is not a design.
     *
     * So sending continues for as long as the game is live, and the server decides. It refuses
     * anything outside a round with a stop, before storing it — see the position handler,
     * where the liveness check deliberately sits above that refusal.
     *
     * The cost is one refused request per phase change per player, and coordinates that go
     * over the wire during meetings and are discarded rather than never being sent. That is a
     * real weakening of "only while a round is running" and it is worth it: the alternative is
     * a mode where dying arrives late, which is the one place lateness does damage.
     */
    static boolean gameIsLive(BoardData board) {
        if (board == null || !"impostor".equals(board.getGameType())) {
            return false;
        }
        String phase = board.getPhase();
        if ("done".equals(phase) || "reveal".equals(phase)) {
            return false;
        }
        if ("finished".equals(board.getStatus())) {
            return false;
        }
        // The lobby has to be live before anyone is dealt: that is how they ready up.
        return true;
    }

    /**
     * What a stop applies to.
     *
     * Keyed on the room and the phase together so a refusal expires by itself: the server
     * says stop, the phase moves on to the meeting and then into the next round, and the
     * key no longer matches. Without this, one stale board arriving mid-transition would
     * silence the plugin for the rest of the game.
     */
    static String stateKey(String roomCode, String phase) {
        return roomCode + "|" + phase;
    }

    /**
     * Whether this sample is worth sending yet.
     *
     * Two reasons to send: the player has moved, or they have not moved for long enough
     * that silence would be ambiguous. The second is the one that matters and the one a
     * send-on-change-only design would miss — the server accumulates time in a place, so
     * it has to be able to tell somebody standing still killing moss giants apart from
     * somebody who closed the client. Both look identical from the far end of a quiet
     * connection, and guessing wrong either invents an alibi or steals an honest one.
     */
    static boolean dueToSend(Sample last, Sample now, long sinceLastSendMs, long tickMs, long keepaliveMs) {
        if (now == null) {
            return false;
        }
        if (sinceLastSendMs < tickMs) {
            return false;
        }
        if (last == null) {
            return true;
        }
        if (!last.equals(now)) {
            return true;
        }
        return sinceLastSendMs >= keepaliveMs;
    }

    private void poll() {
        try {
            send();
        } catch (RuntimeException e) {
            // A throw here would kill the scheduled task silently and take the feature with
            // it for the rest of the session.
            log.debug("Position poll failed: {}", e.getMessage());
        }
    }

    private void send() {
        BoardData board = dropMatcher.getActiveBoard();
        String room = dropMatcher.getActiveRoomCode();
        String phase = board != null ? board.getPhase() : null;
        String key = stateKey(room, phase);

        // A new round, or a new room, starts from nothing: the server has wiped its own
        // record, so the first sample of a round must always go even from somebody who has
        // not moved an inch since the last one. Tracked on every poll rather than only
        // while a round runs, so the round/meeting/round transition is actually observed.
        if (!Objects.equals(key, lastKey)) {
            lastKey = key;
            lastSent = null;
            failures = 0;
        }

        if (!config.shareImpostorPosition() || !gameIsLive(board) || room == null) {
            return;
        }
        if (key.equals(stoppedKey) || failures >= MAX_FAILURES) {
            return;
        }

        long since = System.currentTimeMillis() - lastSentAt;

        // Between rounds: ask, without saying where we are. The only thing worth hearing here
        // is that we have been killed, and the stop that comes back with the server's refusal
        // means this costs one request per phase rather than one every tick.
        if (!shouldSample(board)) {
            if (since < tickMs) {
                return;
            }
            lastSent = null;
            lastSentAt = System.currentTimeMillis();
            apiClient.reportPosition(PositionPayload.statusOnly(room))
                .thenAccept(ack -> onAck(key, null, ack));
            return;
        }

        Sample now = latest;
        if (!dueToSend(lastSent, now, since, tickMs, keepaliveMs)) {
            return;
        }

        lastSent = now;
        lastSentAt = System.currentTimeMillis();
        apiClient.reportPosition(now.payload(room)).thenAccept(ack -> onAck(key, now, ack));
    }

    /**
     * Apply whatever the server said about the sample we just sent.
     *
     * Runs on an HTTP callback thread, so it only touches the volatile fields and the one
     * scheduler-owned field it has a good reason to: a sample the server did not keep must
     * not be remembered as sent, or the keepalive would be the only thing that ever
     * resent it.
     */
    private void onAck(String key, Sample sent, PositionAck ack) {
        if (ack == null) {
            failures++;
            forget(sent);
            return;
        }
        failures = 0;

        if (ack.getTick() != null && ack.getTick() > 0) {
            tickMs = ack.getTick() * 1000L;
        }
        if (ack.getKeepalive() != null && ack.getKeepalive() > 0) {
            keepaliveMs = ack.getKeepalive() * 1000L;
        }

        // Before the stop, which arrives with it and would otherwise return first.
        if (ack.isDead()) {
            announceDeath();
        }

        if (ack.getRole() != null) {
            role = ack.getRole();
        }
        meeting = ack.isMeeting();
        blackout = ack.isBlackout();
        if (ack.getBodies() != null) {
            bodies = ack.getBodies();
        }

        if (ack.isStop()) {
            stoppedKey = key;
            zone = null;
            log.debug("Position reporting stopped for {}", key);
            return;
        }
        if (ack.isThrottled()) {
            forget(sent);
            return;
        }
        zone = ack.getZone();
    }

    /**
     * Tell the player they are out, once.
     *
     * THE ORDER OF THE WORDS IS THE FEATURE. Everybody is on voice chat, and the honest
     * reaction to a screen flash mid-fight is to say something — which tells the room that a
     * kill just happened and roughly where, and that is most of what the meeting was for. So
     * the instruction comes before the news, because it has to land in the quarter second
     * before somebody reacts, and the news is no use to them anyway.
     *
     * The notifier rather than a hand-rolled flash: it honours whatever the player already
     * chose about notifications, and a plugin that forces a flash on somebody who switched
     * them off is a plugin they uninstall.
     *
     * Idempotent because the server re-asserts a death on each phase change — deliberately,
     * so a missed one still arrives — and the second telling should be silent.
     */
    private void announceDeath() {
        if (dead) {
            return;
        }
        dead = true;
        notifier.notify("Say nothing. You have been eliminated.");
        // addChatMessage does nothing off the client thread. Same hop as DropMatcher and
        // AchievementTracker make, and for the same reason.
        clientThread.invoke(() -> client.addChatMessage(
            ChatMessageType.GAMEMESSAGE, "",
            "<col=ff0000>Say nothing.</col> You have been eliminated. "
                + "Nothing you do from here counts. Stay muted and keep watching.",
            ""));
        log.debug("Player eliminated");
    }

    /** Whether the player has been told they are out, for the overlay that keeps saying so. */
    public boolean isDead() {
        return dead;
    }

    public boolean isImpostor() {
        return "impostor".equals(role);
    }

    public boolean isMeeting() {
        return meeting;
    }

    public boolean isBlackout() {
        return blackout;
    }

    public java.util.List<ImpostorBody> getBodies() {
        return bodies;
    }

    public ImpostorBody bodyAt(int x, int y, int plane) {
        for (ImpostorBody b : bodies) {
            if (b != null && b.getX() == x && b.getY() == y && b.getPlane() == plane) {
                return b;
            }
        }
        return null;
    }

    /**
     * Drop the memory of a sample the server never recorded, so the next poll resends it
     * rather than waiting out the keepalive on a position that was never stored.
     */
    private void forget(Sample sent) {
        if (lastSent == sent) {
            lastSent = null;
        }
    }

    /** The zone the server last placed us in, or null. Exposed for logging and the panel. */
    public String getZone() {
        return zone;
    }

    /**
     * One reading of the player's position.
     *
     * Immutable and compared by value, because "has it changed" is the whole question the
     * send decision asks. The plane and the instance flag are part of that: moving between
     * floors of Draynor Manor is movement, and stepping into an instance takes you off the
     * board entirely, which the server needs to hear about promptly.
     */
    static final class Sample {
        private final int x;
        private final int y;
        private final int plane;
        private final boolean instance;

        Sample(int x, int y, int plane, boolean instance) {
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.instance = instance;
        }

        PositionPayload payload(String roomCode) {
            return new PositionPayload(roomCode, x, y, plane, instance);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Sample)) {
                return false;
            }
            Sample other = (Sample) o;
            return x == other.x && y == other.y && plane == other.plane && instance == other.instance;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, plane, instance);
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + "," + plane + (instance ? ",instance" : "") + ")";
        }
    }
}
