package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

@Slf4j
@Singleton
public class ScreenshotHelper {

    @Inject
    private DrawManager drawManager;

    /**
     * Request a screenshot on the next rendered frame.
     * The callback is invoked asynchronously with the JPEG bytes (or null on failure).
     * This does NOT block the calling thread.
     */
    public void captureAsync(Consumer<byte[]> callback) {
        try {
            drawManager.requestNextFrameListener(image -> {
                try {
                    if (image == null) {
                        callback.accept(null);
                        return;
                    }

                    BufferedImage buffered;
                    if (image instanceof BufferedImage) {
                        buffered = (BufferedImage) image;
                    } else {
                        buffered = new BufferedImage(
                            image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = buffered.createGraphics();
                        g.drawImage(image, 0, 0, null);
                        g.dispose();
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(buffered, "jpg", baos);
                    byte[] bytes = baos.toByteArray();
                    log.debug("Screenshot captured: {} bytes", bytes.length);
                    callback.accept(bytes);
                } catch (Exception e) {
                    log.error("Failed to process screenshot", e);
                    callback.accept(null);
                }
            });
        } catch (Exception e) {
            log.error("Failed to request screenshot", e);
            callback.accept(null);
        }
    }
}
