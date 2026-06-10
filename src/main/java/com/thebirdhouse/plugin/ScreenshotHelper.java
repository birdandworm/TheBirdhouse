package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

@Slf4j
@Singleton
public class ScreenshotHelper {

    @Inject
    private DrawManager drawManager;

    public byte[] capture() {
        try {
            final Image[] imageHolder = new Image[1];
            final CountDownLatch latch = new CountDownLatch(1);

            drawManager.requestNextFrameListener(image -> {
                imageHolder[0] = image;
                latch.countDown();
            });

            if (!latch.await(3, TimeUnit.SECONDS)) {
                log.warn("Screenshot capture timed out");
                return null;
            }

            Image raw = imageHolder[0];
            if (raw == null) return null;

            BufferedImage buffered;
            if (raw instanceof BufferedImage) {
                buffered = (BufferedImage) raw;
            } else {
                buffered = new BufferedImage(
                    raw.getWidth(null), raw.getHeight(null), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = buffered.createGraphics();
                g.drawImage(raw, 0, 0, null);
                g.dispose();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(buffered, "jpg", baos);
            byte[] bytes = baos.toByteArray();
            log.debug("Screenshot captured: {} bytes", bytes.length);
            return bytes;
        } catch (Exception e) {
            log.error("Failed to capture screenshot", e);
            return null;
        }
    }
}
