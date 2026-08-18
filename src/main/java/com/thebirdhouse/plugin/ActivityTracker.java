package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accumulates leaderboard activity locally and flushes a compact delta on each
 * session heartbeat + logout. Tracks:
 *   - active time: only game ticks where XP or a kill happened recently (anti-AFK),
 *   - NPC kills: one per NPC loot event, tallied per monster, except for the handful of
 *     sources that pay out on failure (see KC_CONFIRMED_SOURCES),
 *   - loot GP: GE value of every drop, no minimum.
 *
 * Every flush is a DELTA — the server folds it into a running per-room total — so
 * accumulators reset after each flush. Event handlers run on the client thread while
 * flush() runs on the session scheduler thread, so shared accumulators are guarded.
 */
@Slf4j
@Singleton
public class ActivityTracker {

    // Treat the player as "active" if XP or a kill happened within this window.
    private static final long ACTIVE_IDLE_MS = 60_000;
    // Clamp a single tick's contribution so a paused/laggy client can't over-count.
    private static final long MAX_TICK_MS = 1_200;

    /**
     * Sources whose loot is NOT proof of a kill, keyed by the name the game announces in
     * the killcount message to the name the loot event arrives under.
     *
     * The Gauntlet hands you a consolation chest when you die, and RuneLite books that
     * chest against whichever boss you last fought — so a failed run looks exactly like a
     * completed one to a loot listener. That matters well beyond the leaderboard now that
     * The Delve pays supplies per kill: a death was earning the full Hunllef rate.
     *
     * These are credited from the game's own completion count instead, which only ever
     * fires on a win. Note the two names genuinely differ: the Gauntlet reports a
     * completion count for the minigame while the loot is booked against the boss.
     */
    private static final Map<String, String> KC_CONFIRMED_SOURCES = Map.of(
        "Corrupted Gauntlet", "Corrupted Hunllef",
        "Gauntlet", "Crystalline Hunllef"
    );

    // Mirrors RuneLite's own ChatCommandsPlugin killcount pattern, which has absorbed
    // years of per-boss phrasing quirks ("subdued", "completion count for", raid
    // "completed" counts). The trailing colour tag is optional only so the pattern stays
    // testable against plain strings.
    private static final Pattern KILLCOUNT_PATTERN = Pattern.compile(
        "Your (?:completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?"
            + "(?<boss>.+?)(?:</col>)? "
            + "(?:(?:kill|harvest|lap|completion|success) )?(?:count )?"
            + "is: ?(?:<col=[0-9a-f]{6}>)?(?<kc>[0-9,]+)"
    );

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private DropMatcher dropMatcher;

    private volatile String activeRoomCode;

    // Accumulators (guarded by 'this').
    private long activeMsAccum = 0;
    private long lootGpAccum = 0;
    private int killsAccum = 0;
    private long biggestDrop = 0;
    private String biggestSource = null;
    private final Map<String, Integer> npcKills = new HashMap<>();

    // Client-thread-only activity clock.
    private long lastActivityMs = 0;
    private long lastTickMs = 0;

    public void setActiveRoom(String roomCode) {
        this.activeRoomCode = roomCode;
    }

    /** Clears all local state — used when a new session starts. */
    public void reset() {
        clearAccumulators();
        lastActivityMs = 0;
        lastTickMs = 0;
    }

    private void clearAccumulators() {
        synchronized (this) {
            activeMsAccum = 0;
            lootGpAccum = 0;
            killsAccum = 0;
            biggestDrop = 0;
            biggestSource = null;
            npcKills.clear();
        }
    }

    private void markActive() {
        lastActivityMs = System.currentTimeMillis();
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        // Any XP gain counts as activity (covers cannon / afk-combat / skilling that
        // WOM's EHP/EHB largely miss).
        markActive();
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!config.contributeActivityStats()) return;
        long now = System.currentTimeMillis();
        long prev = lastTickMs;
        lastTickMs = now;
        if (prev == 0) return;
        if (now - lastActivityMs <= ACTIVE_IDLE_MS) {
            long delta = Math.min(now - prev, MAX_TICK_MS);
            if (delta > 0) {
                synchronized (this) { activeMsAccum += delta; }
            }
        }
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        if (!config.contributeActivityStats()) return;
        // Only monster kills (NPC loot) count — not player kills, clues, or events.
        // Compared by name to avoid a version-specific enum import.
        if (!"NPC".equals(String.valueOf(event.getType()))) return;

        markActive();

        String source = event.getName();
        long eventValue = 0;
        long topStack = 0;
        for (ItemStack stack : event.getItems()) {
            int price = 0;
            try {
                price = itemManager.getItemPrice(stack.getId());
            } catch (Exception ignored) {
                // Untradeable / unknown item — worth 0.
            }
            long stackVal = (long) Math.max(0, price) * Math.max(0, stack.getQuantity());
            eventValue += stackVal;
            if (stackVal > topStack) topStack = stackVal;
        }

        // The GP is real whether or not the run was won, so it is banked either way; only
        // the kill waits for confirmation from the killcount message.
        boolean awaitKillcount = source != null && KC_CONFIRMED_SOURCES.containsValue(source);

        synchronized (this) {
            lootGpAccum += eventValue;
            if (!awaitKillcount) {
                killsAccum += 1;
                if (source != null && !source.isEmpty()) {
                    npcKills.merge(source, 1, Integer::sum);
                }
            }
            if (topStack > biggestDrop) {
                biggestDrop = topStack;
                biggestSource = source;
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!config.contributeActivityStats()) return;
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;

        Matcher matcher = KILLCOUNT_PATTERN.matcher(event.getMessage());
        if (!matcher.find()) return;

        // Only the sources held back in onLootReceived are credited here. Every other boss
        // was already counted from its loot, and counting it again would double it.
        String source = KC_CONFIRMED_SOURCES.get(matcher.group("boss"));
        if (source == null) return;

        markActive();
        synchronized (this) {
            killsAccum += 1;
            npcKills.merge(source, 1, Integer::sum);
        }
    }

    /**
     * Send the accumulated delta and reset. Called from the session heartbeat and on
     * logout. No-ops when tracking is disabled or there's nothing to report.
     */
    public void flush() {
        if (!config.contributeActivityStats()) {
            reset();
            return;
        }

        // Once the event is over the server discards the delta, so sending it buys nothing
        // and would repeat every flush interval for as long as the plugin stays installed.
        // The accumulators are dropped rather than held: keeping them would bank hours of
        // unrelated play and dump the lot into whichever event the player joins next.
        if (dropMatcher.isEventOver()) {
            clearAccumulators();
            return;
        }

        ActivityPayload payload;
        synchronized (this) {
            if (activeMsAccum == 0 && killsAccum == 0 && lootGpAccum == 0) {
                return;
            }
            payload = new ActivityPayload();
            payload.setBatch(true);
            payload.setRoomCode(activeRoomCode);
            payload.setActiveMs(activeMsAccum);
            payload.setLootGp(lootGpAccum);
            payload.setKills(killsAccum);
            payload.setBiggest(biggestDrop);
            payload.setBiggestSource(biggestSource);
            payload.setNpcKills(new HashMap<>(npcKills));

            // Reset the delta accumulators; the server keeps the running total. We
            // intentionally keep biggestDrop as a session-local max reference reset
            // here too, since the server tracks the running max independently.
            activeMsAccum = 0;
            lootGpAccum = 0;
            killsAccum = 0;
            biggestDrop = 0;
            biggestSource = null;
            npcKills.clear();
        }

        apiClient.reportActivity(payload);
    }
}
