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
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-game verbs for The Impostor: Eliminate, Report body, Cut the lights.
 *
 * Offered only when the position ack or board poll says this player may use them.
 * The server still refuses anything that is not legal.
 */
@Slf4j
@Singleton
public class ImpostorActions {

    private static final String ELIMINATE = "Eliminate";
    private static final String REPORT = "Report body";
    private static final String LIGHTS = "Cut the lights";

    /**
     * Same Chebyshev reach the server uses in {@code canKill}. The side panel and
     * the right-click verb both stop at this number; the server is what actually
     * decides, so a forged click still fails if the pings are farther apart.
     */
    static final int KILL_RANGE_TILES = 8;

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
        maskMenuNames();
    }

    /**
     * Insert an Eliminate per player once the menu is fully built. Gating on PLAYER_FIRST_OPTION
     * missed the verb whenever Follow was not the first player row — Walk here,
     * plugin lookups and a custom left-click all skip that opcode.
     */
    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (tracker.isBlackout() && !tracker.isImpostor() && !tracker.isDead()) {
            maskMenuNames();
        }
        if (tracker.isDead() || !tracker.isImpostor()) {
            return;
        }
        if (!commandsAllowed(dropMatcher.getActiveBoard(), tracker.getPhase())) {
            return;
        }
        String room = dropMatcher.getActiveRoomCode();
        if (room == null) {
            return;
        }
        for (String name : otherPlayerNames(event.getMenuEntries())) {
            insert(ELIMINATE, ColorUtil.wrapWithColorTag(name, Color.RED), () ->
                runAction(apiClient.impostorKill(room, name)));
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

        if (REPORT.equals(event.getOption()) || LIGHTS.equals(event.getOption())
            || ELIMINATE.equals(event.getOption())) {
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
            ImpostorBody body = tracker.bodyNear(tile.getX(), tile.getY(), tile.getPlane(), 8);
            if (body != null) {
                String label = body.getName() == null || body.getName().isEmpty() ? "a body" : body.getName();
                insert(REPORT, "<col=" + Integer.toHexString(Color.RED.getRGB() & 0xffffff) + ">" + label + "</col>",
                    () -> runAction(apiClient.impostorReport(room)));
            }
        }
    }

    private void maskMenuNames() {
        Player me = client.getLocalPlayer();
        String mine = me != null ? Text.removeTags(me.getName()) : null;
        Set<String> others = nearbyNames(me);
        if (others.isEmpty()) {
            return;
        }
        for (MenuEntry entry : client.getMenuEntries()) {
            if (ELIMINATE.equals(entry.getOption()) || REPORT.equals(entry.getOption())
                || LIGHTS.equals(entry.getOption())) {
                continue;
            }
            String raw = Text.removeTags(entry.getTarget());
            if (raw == null || raw.isEmpty() || "???".equals(raw)) {
                continue;
            }
            String core = playerName(raw);
            if (mine != null && mine.equalsIgnoreCase(core)) {
                continue;
            }
            if (namesPlayer(raw, core, others)) {
                entry.setTarget(ColorUtil.wrapWithColorTag("???", Color.WHITE));
            }
        }
    }

    private Set<String> nearbyNames(Player me) {
        Set<String> names = new HashSet<>();
        for (Player other : client.getPlayers()) {
            if (other == null || other == me || other.getName() == null) {
                continue;
            }
            names.add(Text.removeTags(other.getName()).toLowerCase());
        }
        return names;
    }

    static boolean namesPlayer(String raw, String core, Set<String> others) {
        if (core != null && !core.isEmpty() && others.contains(core.toLowerCase())) {
            return true;
        }
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase();
        for (String name : others) {
            if (!name.isEmpty() && lower.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every distinct player under the cursor, in menu order. Taking only the first
     * player row left a stack of crewmates on one tile reachable by whichever name
     * RuneLite happened to list first, with no way to aim at the others. Names are
     * de-duplicated so a player with several rows (Follow, Trade, a plugin lookup)
     * still contributes a single Eliminate.
     */
    private Collection<String> otherPlayerNames(MenuEntry[] entries) {
        if (entries == null) {
            return java.util.Collections.emptyList();
        }
        List<String> targets = new ArrayList<>();
        for (MenuEntry entry : entries) {
            if (entry != null && isPlayerMenu(entry.getType().getId())) {
                targets.add(entry.getTarget());
            }
        }
        Player me = client.getLocalPlayer();
        return distinctOtherNames(targets, me != null ? Text.removeTags(me.getName()) : null);
    }

    /**
     * The filtering half of {@link #otherPlayerNames}, split out so it can be
     * exercised without a live client.
     */
    static Collection<String> distinctOtherNames(List<String> playerTargets, String mine) {
        Set<String> names = new LinkedHashSet<>();
        if (playerTargets == null) {
            return names;
        }
        for (String target : playerTargets) {
            String name = playerName(target);
            if (name.isEmpty() || "???".equals(name)) {
                continue;
            }
            if (mine != null && mine.equalsIgnoreCase(name)) {
                continue;
            }
            names.add(name);
        }
        return names;
    }

    private static boolean isPlayerMenu(int type) {
        return type >= MenuAction.PLAYER_FIRST_OPTION.getId()
            && type <= MenuAction.PLAYER_EIGHTH_OPTION.getId();
    }

    static String playerName(String target) {
        if (target == null) {
            return "";
        }
        return Text.removeTags(target).replaceAll("(?i)\\s*\\(level-?\\d+\\)", "").trim();
    }

    private void insert(String option, String target, Runnable action) {
        MenuEntry entry = client.getMenu().createMenuEntry(-1);
        entry.setOption(option);
        entry.setTarget(target);
        entry.setType(MenuAction.RUNELITE);
        entry.onClick(e -> action.run());
    }

    /**
     * Chebyshev distance, same as the server. Used to decide who the side panel
     * offers — never as the kill itself.
     */
    static boolean withinKillRange(int x1, int y1, int plane1, int x2, int y2, int plane2) {
        if (plane1 != plane2) {
            return false;
        }
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2)) <= KILL_RANGE_TILES;
    }

    void runAction(java.util.concurrent.CompletableFuture<String> future) {
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
