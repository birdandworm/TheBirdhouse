package com.thebirdhouse.plugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BirdhousePluginTest {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(BirdhousePlugin.class);
        RuneLite.main(args);
    }
}
