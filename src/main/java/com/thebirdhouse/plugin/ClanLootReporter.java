package com.thebirdhouse.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports loot drops, clue completions, and collection-log entries to the clan
 * leaderboard via POST /api/plugin/clan-loot. This replaces the Dink dynamic-
 * config pipeline so clan members no longer need Dink installed for the Discord
 * drop tracker to work.
 *
 * Completely independent of the bingo game path (DropMatcher / submit-proof).
 * Gated on the contributeClanStats toggle and a non-empty clanId in config.
 */
@Slf4j
@Singleton
public class ClanLootReporter {

    private static final long MIN_LOOT_VALUE = 100_000;

    private static final Pattern COLLECTION_LOG_PATTERN =
        Pattern.compile("New item added to your collection log: (.+)");

    private static final Pattern CLUE_COMPLETE_PATTERN =
        Pattern.compile("You have completed (\\d+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.");

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private BirdhouseConfig config;

    @Inject
    private BirdhouseApiClient apiClient;

    private boolean isEnabled() {
        return config.contributeClanStats()
            && config.clanId() != null
            && !config.clanId().trim().isEmpty();
    }

    private String clanId() {
        return config.clanId().trim().toLowerCase();
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        if (!isEnabled()) return;
        String lootType = String.valueOf(event.getType());
        if (!"NPC".equals(lootType) && !"EVENT".equals(lootType)) return;

        String source = event.getName();
        JsonArray items = new JsonArray();
        long totalValue = 0;

        for (ItemStack stack : event.getItems()) {
            ItemComposition comp = itemManager.getItemComposition(stack.getId());
            int price = 0;
            try {
                price = itemManager.getItemPrice(stack.getId());
            } catch (Exception ignored) {
            }
            long stackVal = (long) Math.max(0, price) * Math.max(0, stack.getQuantity());
            totalValue += stackVal;

            JsonObject item = new JsonObject();
            item.addProperty("name", comp.getName());
            item.addProperty("id", stack.getId());
            item.addProperty("quantity", stack.getQuantity());
            item.addProperty("priceEach", Math.max(0, price));
            items.add(item);
        }

        if (totalValue < MIN_LOOT_VALUE) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "LOOT");
        payload.addProperty("clanId", clanId());
        payload.addProperty("playerName", client.getLocalPlayer().getName());
        payload.add("items", items);
        payload.addProperty("source", source);
        payload.addProperty("totalValue", totalValue);

        apiClient.reportClanLoot(payload);
        log.debug("[Birdhouse] Clan loot reported: {} from {} ({}gp)", items.size(), source, totalValue);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!isEnabled()) return;
        if (event.getType() != ChatMessageType.GAMEMESSAGE
            && event.getType() != ChatMessageType.SPAM) return;

        String message = event.getMessage();
        if (message == null) return;
        String clean = message.replaceAll("<[^>]+>", "").trim();

        Matcher collLog = COLLECTION_LOG_PATTERN.matcher(clean);
        if (collLog.find()) {
            String itemName = collLog.group(1);

            JsonObject payload = new JsonObject();
            payload.addProperty("type", "COLLECTION");
            payload.addProperty("clanId", clanId());
            payload.addProperty("playerName", client.getLocalPlayer().getName());
            payload.addProperty("itemName", itemName);

            apiClient.reportClanLoot(payload);
            log.debug("[Birdhouse] Clan collection log reported: {}", itemName);
            return;
        }

        Matcher clue = CLUE_COMPLETE_PATTERN.matcher(clean);
        if (clue.find()) {
            String clueType = clue.group(2).toLowerCase();

            JsonObject payload = new JsonObject();
            payload.addProperty("type", "CLUE");
            payload.addProperty("clanId", clanId());
            payload.addProperty("playerName", client.getLocalPlayer().getName());
            payload.addProperty("clueType", clueType);
            payload.addProperty("source", "Clue scroll (" + clueType + ")");
            payload.add("items", new JsonArray());

            apiClient.reportClanLoot(payload);
            log.debug("[Birdhouse] Clan clue reported: {}", clueType);
        }
    }
}
