package com.thebirdhouse.plugin;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;

/**
 * Draws corpses the server has disclosed for this zone. There is no real body in OSRS;
 * everyone in the room has the plugin, so the plugin is the corpse.
 */
@Singleton
public class ImpostorBodyOverlay extends Overlay {

    private static final Color FILL = new Color(180, 0, 0, 80);
    private static final Color BORDER = new Color(220, 40, 40, 220);

    @Inject
    private Client client;

    @Inject
    private ImpostorPositionTracker tracker;

    @Inject
    public ImpostorBodyOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (tracker.isDead()) {
            return null;
        }
        for (ImpostorBody body : tracker.getBodies()) {
            if (body == null) {
                continue;
            }
            WorldPoint world = new WorldPoint(body.getX(), body.getY(), body.getPlane());
            LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), world);
            if (local == null) {
                continue;
            }
            Polygon tile = Perspective.getCanvasTilePoly(client, local);
            if (tile == null) {
                continue;
            }
            graphics.setColor(FILL);
            graphics.fill(tile);
            graphics.setColor(BORDER);
            graphics.draw(tile);
            String label = body.getName() == null || body.getName().isEmpty() ? "Body" : body.getName();
            Point text = Perspective.getCanvasTextLocation(client, graphics, local, label, 0);
            if (text != null) {
                graphics.setColor(Color.RED);
                graphics.drawString(label, text.getX(), text.getY());
            }
        }
        return null;
    }
}
