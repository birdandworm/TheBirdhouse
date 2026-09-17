package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;

/**
 * In-game verbs for The Impostor: Eliminate, Report body, Cut the lights.
 *
 * Offered only when the position ack says this player may use them. The server still
 * refuses anything that is not legal — the menu is so a crewmate never sees Eliminate.
 */
@Slf4j
@Singleton
public class ImpostorActions {

    private static final String ELIMINATE = "Eliminate";
    private static final String REPORT = "Report body";
    private static final String LIGHTS = "Cut the lights";

    @Inject
    private Client client;

    @Inject
    private ImpostorPositionTracker tracker;

    @Inject
    private BirdhouseApiClient apiClient;

    @Inject
    private DropMatcher dropMatcher;

    @Inject
    private ClientThread clientThread;

    /**
     * Eliminate, Report body and Cut the lights exist only while this client is attached
     * to a live Impostor round. A leftover role from an earlier game must not put those
     * options on a bingo board — or any other mode.
     */
    static boolean commandsAllowed(BoardData board) {
        return commandsAllowed(board, null);
    }

    /**
     * Prefer the phase from the position ack — that arrives within seconds of a deal.
     * The board poll is a minute behind, so gating only on it hid Eliminate for the
     * whole opening of the game.
     */
    static boolean commandsAllowed(BoardData board, String livePhase) {
        if (board == null || !"impostor".equals(board.getGameType())) {
            return false;
        }
        String phase = (livePhase != null && !livePhase.isEmpty()) ? livePhase : board.getPhase();
        return "round".equals(phase);
    }

    @Subscribe
    public void onClientTick(ClientTick event) {
        if (!commandsAllowed(dropMatcher.getActiveBoard(), tracker.getPhase())) {
            return;
        }
        if (!tracker.isBlackout() || tracker.isImpostor() || tracker.isDead()) {
            return;
        }
        Player me = client.getLocalPlayer();
        String mine = me != null ? me.getName() : null;
        for (MenuEntry entry : client.getMenuEntries()) {
            if (!isPlayerMenu(entry.getType().getId())) {
                continue;
            }
            String name = Text.removeTags(entry.getTarget());
            if (mine != null && mine.equals(name)) {
                continue;
            }
            entry.setTarget(ColorUtil.wrapWithColorTag("???", Color.WHITE));
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (tracker.isDead()) {
            return;
        }
        if (!commandsAllowed(dropMatcher.getActiveBoard(), tracker.getPhase())) {
            return;
        }
        String room = dropMatcher.getActiveRoomCode();
        if (room == null) {
            return;
        }

        if (tracker.isImpostor() && isFirstPlayerOption(event.getType())) {
            String target = event.getTarget();
            Player me = client.getLocalPlayer();
            String mine = me != null ? me.getName() : null;
            if (mine == null || !mine.equals(Text.removeTags(target))) {
                insert(ELIMINATE, target, () ->
                    runAction(apiClient.impostorKill(room, playerName(target))));
            }
        }

        if (REPORT.equals(event.getOption()) || LIGHTS.equals(event.getOption())) {
            return;
        }
        if ("Walk here".equals(event.getOption())) {
            if (tracker.isImpostor()) {
                insert(LIGHTS, "", () -> runAction(apiClient.impostorSabotage(room)));
            }
            WorldPoint tile = WorldPoint.fromScene(
                client.getTopLevelWorldView(),
                event.getActionParam0(),
                event.getActionParam1(),
                client.getTopLevelWorldView().getPlane());
            ImpostorBody body = tracker.bodyAt(tile.getX(), tile.getY(), tile.getPlane());
            if (body != null) {
                String label = body.getName() == null || body.getName().isEmpty() ? "a body" : body.getName();
                insert(REPORT, "<col=" + Integer.toHexString(Color.RED.getRGB() & 0xffffff) + ">" + label + "</col>",
                    () -> runAction(apiClient.impostorReport(room)));
            }
        }
    }

    private static boolean isPlayerMenu(int type) {
        return type >= MenuAction.PLAYER_FIRST_OPTION.getId()
            && type <= MenuAction.PLAYER_EIGHTH_OPTION.getId();
    }

    /**
     * MenuEntryAdded fires once per existing player row (Follow, Trade, …).
     * Inserting on every row stacked Eliminate three times.
     */
    static boolean isFirstPlayerOption(int type) {
        return type == MenuAction.PLAYER_FIRST_OPTION.getId();
    }

    static String playerName(String target) {
        return Text.removeTags(target).replaceAll("(?i)\\s*\\(level-?\\d+\\)", "").trim();
    }

    private void insert(String option, String target, Runnable action) {
        MenuEntry entry = client.getMenu().createMenuEntry(-1);
        entry.setOption(option);
        entry.setTarget(target);
        entry.setType(MenuAction.RUNELITE);
        entry.onClick(e -> action.run());
    }

    private void runAction(java.util.concurrent.CompletableFuture<String> future) {
        future.thenAccept(error -> {
            if (error == null || error.isEmpty()) {
                return;
            }
            clientThread.invoke(() -> client.addChatMessage(
                ChatMessageType.GAMEMESSAGE, "",
                "<col=ff9040>" + error + "</col>",
                ""));
        });
    }
}
