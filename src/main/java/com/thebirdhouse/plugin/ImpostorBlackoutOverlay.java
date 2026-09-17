package com.thebirdhouse.plugin;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Covers the name over each other player's head while the lights are out.
 *
 * The right-click menu was never enough: OSRS still writes the RSN above them, and
 * leaving clan chat does not stop that. This paints over the name so crew cannot read
 * who they just walked past. The impostor is exempt.
 */
@Singleton
public class ImpostorBlackoutOverlay extends Overlay {

    private static final Color SLAB = new Color(12, 12, 16, 230);
    private static final Color LABEL = Color.WHITE;

    @Inject
    private Client client;

    @Inject
    private ImpostorPositionTracker tracker;

    @Inject
    private DropMatcher dropMatcher;

    @Inject
    public ImpostorBlackoutOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!shouldMask(dropMatcher.getActiveBoard(), tracker.getPhase(), tracker.isBlackout(), tracker.isImpostor(), tracker.isDead())) {
            return null;
        }
        Player me = client.getLocalPlayer();
        FontMetrics metrics = graphics.getFontMetrics();
        for (Player other : client.getPlayers()) {
            if (other == null || other == me) {
                continue;
            }
            String name = Text.removeTags(other.getName());
            if (name == null || name.isEmpty()) {
                continue;
            }
            Point loc = other.getCanvasTextLocation(graphics, name, other.getLogicalHeight() + 40);
            if (loc == null) {
                continue;
            }
            paintMask(graphics, metrics, loc, name);
        }
        return null;
    }

    static boolean shouldMask(BoardData board, boolean blackout, boolean impostor, boolean dead) {
        return shouldMask(board, null, blackout, impostor, dead);
    }

    static boolean shouldMask(BoardData board, String livePhase, boolean blackout, boolean impostor, boolean dead) {
        return ImpostorActions.commandsAllowed(board, livePhase) && blackout && !impostor && !dead;
    }

    static Rectangle slabFor(FontMetrics metrics, Point loc, String name) {
        int width = Math.max(metrics.stringWidth(name), metrics.stringWidth("???")) + 16;
        int height = metrics.getHeight() + 8;
        return new Rectangle(loc.getX() - width / 2, loc.getY() - height + 4, width, height);
    }

    private static void paintMask(Graphics2D graphics, FontMetrics metrics, Point loc, String name) {
        Rectangle slab = slabFor(metrics, loc, name);
        graphics.setColor(SLAB);
        graphics.fill(slab);
        graphics.setColor(LABEL);
        int x = loc.getX() - metrics.stringWidth("???") / 2;
        graphics.drawString("???", x, loc.getY());
    }
}
