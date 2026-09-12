package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.loottracker.LootReceived;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/**
 * Kill-count tiles for bosses whose loot is not booked against them.
 *
 * A kill-count tile is satisfied by a loot event whose source name matches, which works
 * for anything that dies and drops. It silently fails for the bosses that hand out their
 * rewards through something else: the loot tracker books Wintertodt's crates as "Supply
 * crate (Wintertodt)" and Tempoross's pool as "Reward pool (Tempoross)", so a tile asking
 * for "Wintertodt" never sees a thing. The kill is real, the game announces it, and the
 * counter goes up — it just never reaches the board.
 *
 * That announcement is the signal used here. The same message already confirms kills for
 * the leaderboard, so the pattern is shared rather than rewritten.
 *
 * Most bosses announce a count *and* drop loot, which would credit the tile twice. A
 * credit is held for a tick and dropped if the loot tracker named the same source, which
 * covers the ordinary case without a wasted upload. Nothing behind that catches what this
 * misses: the server collapses the rolls that shared a {@link ProofPayload#killId}, and an
 * announcement carries an id of its own precisely because it is a separate kill.
 */
@Slf4j
@Singleton
public class KillCountTracker {

    /** How long a loot event's source name suppresses a matching killcount message. */
    private static final int RECENT_TICKS = 2;

    @Inject
    private Client client;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private DropMatcher dropMatcher;

    /** Lowercased loot source to the tick it last arrived on. */
    private final Map<String, Integer> lootSourceTicks = new HashMap<>();

    private String pendingBoss = null;
    private int pendingTick = Integer.MIN_VALUE;

    public void reset() {
        lootSourceTicks.clear();
        pendingBoss = null;
        pendingTick = Integer.MIN_VALUE;
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        String name = event.getName();
        if (name == null || name.isEmpty()) return;
        lootSourceTicks.put(name.toLowerCase(), client.getTickCount());
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!config.autoSubmitDrops()) return;
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;

        String boss = KillCounts.bossFrom(event.getMessage());
        if (boss == null) return;

        // Held for a tick rather than credited here. A boss that drops loot fires both
        // signals on the same tick and the order two subscribers run in is undefined, so
        // asking now whether the loot already claimed the kill would be a coin flip.
        pendingBoss = boss;
        pendingTick = client.getTickCount();
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (pendingBoss == null) return;
        if (client.getTickCount() == pendingTick) return;

        String boss = pendingBoss;
        int announced = pendingTick;
        pendingBoss = null;

        if (creditedByLoot(boss, announced, lootSourceTicks)) {
            log.debug("[Birdhouse] '{}' kill already credited from its loot", boss);
            return;
        }

        log.info("[Birdhouse] Kill count announced for '{}'", boss);
        dropMatcher.handleKillCount(boss);
    }

    /**
     * Whether the loot tracker already named this source around the same tick.
     *
     * Kept pure so the double-credit rule can be tested without a client. Wintertodt is
     * the case that must come through: its loot arrives under a different name, so
     * nothing here suppresses it.
     */
    static boolean creditedByLoot(String boss, int announcedTick, Map<String, Integer> lootTicks) {
        if (boss == null || lootTicks == null || lootTicks.isEmpty()) return false;
        Integer seen = lootTicks.get(boss.toLowerCase());
        if (seen == null) return false;
        return Math.abs(announcedTick - seen) <= RECENT_TICKS;
    }
}
