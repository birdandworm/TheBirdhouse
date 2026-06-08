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
        name = "Activity Tracking",
        description = "Session and playtime tracking",
        position = 2
    )
    String activitySection = "activity";

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
}
