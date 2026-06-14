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
                        log.warn("[Birdhouse] Screenshot frame was null");
                        callback.accept(null);
                        return;
                    }

                    int w = image.getWidth(null);
                    int h = image.getHeight(null);
                    if (w <= 0 || h <= 0) {
                        log.warn("[Birdhouse] Screenshot has invalid dimensions: {}x{}", w, h);
                        callback.accept(null);
                        return;
                    }

                    // Always convert to TYPE_INT_RGB — JPEG cannot encode alpha channels
                    // and GPU plugins may return ARGB frames that fail silently
                    BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = rgb.createGraphics();
                    g.drawImage(image, 0, 0, null);
                    g.dispose();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    boolean written = ImageIO.write(rgb, "jpg", baos);
                    if (!written || baos.size() == 0) {
                        log.warn("[Birdhouse] ImageIO.write failed or produced 0 bytes (written={})", written);
                        callback.accept(null);
                        return;
                    }

                    byte[] bytes = baos.toByteArray();
                    log.info("[Birdhouse] Screenshot captured: {} bytes ({}x{})", bytes.length, w, h);
                    callback.accept(bytes);
                } catch (Exception e) {
                    log.error("[Birdhouse] Failed to process screenshot", e);
                    callback.accept(null);
                }
            });
        } catch (Exception e) {
            log.error("[Birdhouse] Failed to request screenshot frame", e);
            callback.accept(null);
        }
    }
}
