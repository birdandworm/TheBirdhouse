package com.thebirdhouse.plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's own "your X count is: N" message.
 *
 * Some bosses never produce loot the tracker can attribute to them. Wintertodt and
 * Tempoross hand out crates and reward pools booked under those names rather than the
 * boss's, so a kill-count tile naming the boss sees nothing at all — even though the game
 * announced the kill in chat and incremented a counter for it.
 *
 * Shared by the leaderboard, which uses it to confirm kills whose loot lies, and by
 * {@link KillCountTracker}, which uses it to credit kill-count tiles. One copy, because
 * two hand-maintained versions of a pattern this fiddly would drift.
 */
final class KillCounts {

    /**
     * Mirrors RuneLite's own ChatCommandsPlugin killcount pattern, which has absorbed
     * years of per-boss phrasing quirks ("subdued", "completion count for", raid
     * "completed" counts). The trailing colour tag is optional only so the pattern stays
     * testable against plain strings.
     */
    static final Pattern KILLCOUNT_PATTERN = Pattern.compile(
        "Your (?:completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?"
            + "(?<boss>.+?)(?:</col>)? "
            + "(?:(?:kill|harvest|lap|completion|success) )?(?:count )?"
            + "is: ?(?:<col=[0-9a-f]{6}>)?(?<kc>[0-9,]+)"
    );

    private KillCounts() {
    }

    /** The boss named by a killcount message, or null if the message is not one. */
    static String bossFrom(String message) {
        if (message == null || message.isEmpty()) return null;
        Matcher matcher = KILLCOUNT_PATTERN.matcher(message);
        if (!matcher.find()) return null;
        String boss = matcher.group("boss");
        return boss == null || boss.isEmpty() ? null : boss;
    }
}
