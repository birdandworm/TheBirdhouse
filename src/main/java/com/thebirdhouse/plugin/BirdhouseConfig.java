package com.thebirdhouse.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("birdhouse")
public interface BirdhouseConfig extends Config {

    @ConfigSection(
        name = "Connection",
        description = "Link your Birdhouse account",
        position = 0
    )
    String connectionSection = "connection";

    @ConfigItem(
        keyName = "authToken",
        name = "Auth Token",
        description = "Your Birdhouse authentication token (found in your account settings)",
        section = connectionSection,
        secret = true,
        position = 0
    )
    default String authToken() {
        return "";
    }

    @ConfigItem(
        keyName = "roomCode",
        name = "Active Room Code",
        description = "The room code for your current game (leave blank to auto-detect)",
        section = connectionSection,
        position = 1
    )
    default String roomCode() {
        return "";
    }

    @ConfigSection(
        name = "Drop Submissions",
        description = "Automatic drop proof settings",
        position = 1
    )
    String dropsSection = "drops";

    @ConfigItem(
        keyName = "autoSubmitDrops",
        name = "Auto-Submit Drops",
        description = "Automatically submit matching drops as proof",
        section = dropsSection,
        position = 0
    )
    default boolean autoSubmitDrops() {
        return true;
    }

    @ConfigItem(
        keyName = "includeScreenshot",
        name = "Include Screenshot",
        description = "Attach a screenshot with each drop submission",
        section = dropsSection,
        position = 1
    )
    default boolean includeScreenshot() {
        return true;
    }

    @ConfigItem(
        keyName = "notifyOnSubmit",
        name = "Chat Notification",
        description = "Show a game chat message when a proof is submitted",
        section = dropsSection,
        position = 2
    )
    default boolean notifyOnSubmit() {
        return true;
    }

    @ConfigSection(
        name = "Board Window",
        description = "The pop-out board window (the sidebar is capped at 225px by RuneLite)",
        position = 2
    )
    String boardWindowSection = "boardWindow";

    @ConfigItem(
        keyName = "openBoardWindowOnStart",
        name = "Open On Login",
        description = "Open the pop-out board window automatically when the plugin starts",
        section = boardWindowSection,
        position = 0
    )
    default boolean openBoardWindowOnStart() {
        return false;
    }

    @ConfigItem(
        keyName = "boardWindowAlwaysOnTop",
        name = "Always On Top",
        description = "Keep the board window above other windows. Also toggled by the window's Pin button.",
        section = boardWindowSection,
        position = 1
    )
    default boolean boardWindowAlwaysOnTop() {
        return false;
    }

    @ConfigSection(
        name = "Team Chat",
        description = "Read and reply to your event team's chat in the panel",
        position = 3,
        closedByDefault = true
    )
    String teamChatSection = "teamChat";

    @ConfigItem(
        keyName = "enableTeamChat",
        name = "Enable Team Chat",
        description = "Show your event team's chat in the panel and the board window, and let you reply to it.",
        section = teamChatSection,
        position = 0,
        warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
    )
    default boolean enableTeamChat() {
        return false;
    }

    @ConfigItem(
        keyName = "teamChatPollSeconds",
        name = "Refresh Seconds",
        description = "How often to check for new team messages while a game is live. Lower feels more like a conversation.",
        section = teamChatSection,
        position = 1
    )
    default int teamChatPollSeconds() {
        return 10;
    }

    @ConfigItem(
        keyName = "notifyOnTeamChat",
        name = "Notify On New Message",
        description = "Fire a RuneLite notification when a teammate posts. Useful mid-fight, when you aren't watching the panel.",
        section = teamChatSection,
        position = 2
    )
    default boolean notifyOnTeamChat() {
        return false;
    }

    @ConfigSection(
        name = "Activity Tracking",
        description = "Session and playtime tracking",
        position = 4
    )
    String activitySection = "activity";

    @ConfigItem(
        keyName = "showOverlay",
        name = "Show Overlay",
        description = "Display tile progress overlay on the game screen",
        section = dropsSection,
        position = 3
    )
    default boolean showOverlay() {
        return true;
    }

    @ConfigItem(
        keyName = "trackActivity",
        name = "Track Play Sessions",
        description = "Report session start/end to The Birdhouse for activity tracking",
        section = activitySection,
        position = 0
    )
    default boolean trackActivity() {
        return true;
    }

    @ConfigItem(
        keyName = "contributeActivityStats",
        name = "Contribute Leaderboard Stats",
        description = "Send active playtime, NPC kills, and total loot value to event leaderboards while you're in an event room. Batched and sent every few minutes.",
        section = activitySection,
        position = 1
    )
    default boolean contributeActivityStats() {
        return true;
    }

    @ConfigSection(
        name = "Clan Leaderboard",
        description = "Report drops to your clan's Discord leaderboard via The Birdhouse (replaces Dink for clan tracking)",
        position = 5
    )
    String clanSection = "clan";

    @ConfigItem(
        keyName = "contributeClanStats",
        name = "Enable Clan Reporting",
        description = "Send loot and collection log data to your clan's leaderboard. This is separate from the bingo game — it feeds the Discord drop tracker.",
        section = clanSection,
        position = 0
    )
    default boolean contributeClanStats() {
        return false;
    }

    @ConfigItem(
        keyName = "clanId",
        name = "Clan ID",
        description = "Your clan's identifier (e.g. 'birdhouse'). Ask your clan leader if you're unsure.",
        section = clanSection,
        position = 1
    )
    default String clanId() {
        return "";
    }
}
