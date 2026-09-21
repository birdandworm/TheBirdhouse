package com.thebirdhouse.plugin;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Meeting and blackout notices. Death has its own overlay; this one is for the two
 * things that change how you play while you are still alive.
 */
@Singleton
public class ImpostorStatusOverlay extends OverlayPanel {

    @Inject
    private ImpostorPositionTracker tracker;

    @Inject
    private BirdhouseConfig config;

    @Inject
    public ImpostorStatusOverlay() {
        setPosition(OverlayPosition.TOP_CENTER);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay() || tracker.isDead()) {
            return null;
        }
        if (tracker.isMeeting()) {
            panelComponent.getChildren().add(TitleComponent.builder()
                .text("MEETING")
                .color(Color.ORANGE)
                .build());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Get on voice. Vote on the website.")
                .build());
            return super.render(graphics);
        }
        if (tracker.isBlackout()) {
            panelComponent.getChildren().add(TitleComponent.builder()
                .text("LIGHTS OUT")
                .color(Color.WHITE)
                .build());
            panelComponent.getChildren().add(LineComponent.builder()
                .left("You cannot see who is standing next to you.")
                .build());
            return super.render(graphics);
        }
        return null;
    }
}
