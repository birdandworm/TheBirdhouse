package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.DrawManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Captures screenshots from the game client for proof submissions.
 */
@Slf4j
@Singleton
public class ScreenshotHelper {

    @Inject
    private DrawManager drawManager;

    /**
     * Capture the current game frame as JPEG bytes.
     * Returns null if capture fails.
     */
    public byte[] capture() {
        try {
            final BufferedImage[] imageHolder = new BufferedImage[1];
            final CountDownLatch latch = new CountDownLatch(1);

            drawManager.requestNextFrameListener(image -> {
                imageHolder[0] = (BufferedImage) image;
                latch.countDown();
            });

            if (!latch.await(2, TimeUnit.SECONDS)) {
                log.warn("Screenshot capture timed out");
                return null;
            }

            if (imageHolder[0] == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(imageHolder[0], "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Failed to capture screenshot", e);
            return null;
        }
    }
}
