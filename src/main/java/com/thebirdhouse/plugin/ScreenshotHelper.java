package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.DrawManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

@Slf4j
@Singleton
public class ScreenshotHelper {

    @Inject
    private DrawManager drawManager;

    @Inject
    private Client client;

    /**
     * Request a screenshot on the next rendered frame.
     * The callback is invoked asynchronously with the JPEG bytes (or null on failure).
     * This does NOT block the calling thread.
     */
    public void captureAsync(Consumer<byte[]> callback) {
        try {
            drawManager.requestNextFrameListener(image -> {
                byte[] result = encodeImage(image, "DrawManager");
                if (result != null) {
                    callback.accept(result);
                } else {
                    log.warn("[Birdhouse] DrawManager capture failed, trying canvas fallback");
                    byte[] fallback = captureFromCanvas();
                    callback.accept(fallback);
                }
            });
        } catch (Exception e) {
            log.error("[Birdhouse] Failed to request screenshot frame, trying canvas fallback", e);
            byte[] fallback = captureFromCanvas();
            callback.accept(fallback);
        }
    }

    private byte[] captureFromCanvas() {
        try {
            Canvas canvas = client.getCanvas();
            if (canvas == null) {
                log.warn("[Birdhouse] Canvas is null");
                return null;
            }

            int w = canvas.getWidth();
            int h = canvas.getHeight();
            if (w <= 0 || h <= 0) {
                log.warn("[Birdhouse] Canvas has invalid size: {}x{}", w, h);
                return null;
            }

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            canvas.paint(g);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean written = ImageIO.write(img, "jpg", baos);
            if (!written || baos.size() == 0) {
                log.warn("[Birdhouse] Canvas fallback: ImageIO.write failed");
                return null;
            }

            byte[] bytes = baos.toByteArray();
            log.info("[Birdhouse] Canvas fallback screenshot: {} bytes ({}x{})", bytes.length, w, h);
            return bytes;
        } catch (Exception e) {
            log.error("[Birdhouse] Canvas fallback failed", e);
            return null;
        }
    }

    private byte[] encodeImage(Image image, String source) {
        try {
            if (image == null) {
                log.warn("[Birdhouse] {} frame was null", source);
                return null;
            }

            int w = image.getWidth(null);
            int h = image.getHeight(null);
            if (w <= 0 || h <= 0) {
                log.warn("[Birdhouse] {} has invalid dimensions: {}x{}", source, w, h);
                return null;
            }

            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean written = ImageIO.write(rgb, "jpg", baos);
            if (!written || baos.size() == 0) {
                log.warn("[Birdhouse] {} ImageIO.write failed (written={})", source, written);
                return null;
            }

            byte[] bytes = baos.toByteArray();
            log.info("[Birdhouse] {} screenshot captured: {} bytes ({}x{})", source, bytes.length, w, h);
            return bytes;
        } catch (Exception e) {
            log.error("[Birdhouse] {} encode failed", source, e);
            return null;
        }
    }
}
