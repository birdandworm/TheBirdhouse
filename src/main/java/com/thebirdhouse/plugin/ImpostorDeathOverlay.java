package com.thebirdhouse.plugin;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Says, and keeps saying, that the player is out of the game.
 *
 * A chat line is the wrong shape for this on its own. It scrolls away in seconds, and it will
 * scroll away while the player is still mid-fight and not reading chat — leaving somebody who
 * has been eliminated with no standing indication of it, still doing tasks that no longer
 * count, still on a voice call they are supposed to be quiet on.
 *
 * DELIBERATELY NOT GATED ON {@code showOverlay}. Every other overlay in this plugin is a
 * convenience and switching it off costs the player nothing but convenience. This one carries
 * the rules of a game they agreed to play, and a ghost who cannot see it is a ghost who
 * forgets they are one. It renders only when there is something to say, which is the whole of
 * its right to ignore the setting.
 */
public class ImpostorDeathOverlay extends OverlayPanel {

    private final ImpostorPositionTracker tracker;

    @Inject
    public ImpostorDeathOverlay(ImpostorPositionTracker tracker) {
        this.tracker = tracker;

        // Top centre, because it is competing with a fight for attention and has to win.
        // Everything else this plugin draws sits top left and can be politely ignored; this
        // cannot. No priority is set: it governs ordering against other overlays rather than
        // prominence, the position is doing the work here, and the enum that would express it
        // is deprecated.
        setPosition(OverlayPosition.TOP_CENTER);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!tracker.isDead()) {
            return null;
        }

        panelComponent.getChildren().add(TitleComponent.builder()
            .text("ELIMINATED")
            .color(Color.RED)
            .build());

        // The instruction, repeated, because it is the part that matters and the part a player
        // in the middle of an argument on voice is most likely to let slip.
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Stay muted")
            .build());

        panelComponent.getChildren().add(LineComponent.builder()
            .left("Nothing counts")
            .build());

        return super.render(graphics);
    }
}
