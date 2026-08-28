package com.thebirdhouse.plugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pictures for board tiles, from the cheapest source that has one.
 *
 * A tile whose match rule names a real item gets the client's own sprite for it. That
 * costs nothing, needs no network, arrives instantly and looks like the game rather
 * than like a web page, so it is always preferred. Only when no item can be resolved
 * does this fall back to the picture the host attached on the site, which is a genuine
 * download and is therefore fetched once and kept for the rest of the session.
 *
 * Every cache here is unbounded on purpose: it is keyed by the tiles of the boards a
 * player actually opened, which is tens of entries, and it must survive the board
 * rebuild that happens on every poll or the fallback images would be re-fetched every
 * minute.
 */
@Slf4j
@Singleton
public class TileIcons {

    /** Item sprites are ~36x32; drawn at native size they stay crisp. */
    private static final int REMOTE_ICON_SIZE = 32;
    private static final long MAX_REMOTE_BYTES = 2L * 1024 * 1024;

    private final ItemManager itemManager;
    private final OkHttpClient httpClient;

    /** Item name (lowercased) to id, with {@link Optional#empty()} as a negative cache. */
    private final Map<String, Optional<Integer>> itemIds = new ConcurrentHashMap<>();
    private final Map<String, ImageIcon> remoteIcons = new ConcurrentHashMap<>();
    private final Set<String> remoteInFlight = ConcurrentHashMap.newKeySet();

    @Inject
    public TileIcons(ItemManager itemManager, OkHttpClient httpClient) {
        this.itemManager = itemManager;
        this.httpClient = httpClient;
    }

    /**
     * Give this label the best icon available for the tile, if any.
     *
     * Returns whether anything will be drawn, so a caller can lay the cell out for text
     * only rather than leaving a hole where an icon never arrives.
     */
    boolean apply(BoardTile tile, JLabel target) {
        if (tile == null) {
            return false;
        }

        Integer itemId = resolveItemId(tile);
        if (itemId != null) {
            AsyncBufferedImage sprite = itemManager.getImage(itemId);
            if (sprite != null) {
                // addTo repaints the label itself once the sprite finishes loading.
                sprite.addTo(target);
                return true;
            }
        }

        String url = tile.getImage();
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        ImageIcon cached = remoteIcons.get(url);
        if (cached != null) {
            target.setIcon(cached);
            return true;
        }

        fetchRemote(url, target);
        // The icon lands later, but the cell should still reserve room for it.
        return true;
    }

    // ===== NATIVE ITEM SPRITES =====

    /**
     * The item id behind a tile, or null when nothing about it names a real item.
     *
     * Tile names are player-authored flavour ("Any Raid Purple", "PET!"), so the match
     * rule is what actually names items and is tried first. Only an exact name match
     * counts: {@link ItemManager#search} is a substring search, so "Shards" would
     * otherwise happily return the first of a hundred unrelated items.
     */
    private Integer resolveItemId(BoardTile tile) {
        for (String candidate : candidateNames(tile)) {
            Optional<Integer> id = itemIds.computeIfAbsent(candidate.toLowerCase(), this::lookupItem);
            if (id.isPresent()) {
                return id.get();
            }
        }
        return null;
    }

    /** Item names this tile could plausibly be represented by, best first. */
    private Set<String> candidateNames(BoardTile tile) {
        Set<String> names = new LinkedHashSet<>();
        addAll(names, tile.getMatchItems());
        if (tile.getMatchGroups() != null) {
            for (BoardTile.MatchGroup group : tile.getMatchGroups()) {
                addAll(names, group.getItems());
            }
        }
        addAll(names, tile.getRequiredItems());
        if (tile.getMatchName() != null && !tile.getMatchName().trim().isEmpty()) {
            String matchName = tile.getMatchName().trim();
            // "Boss - Item" rules name the item after the separator.
            int sep = matchName.indexOf(" - ");
            names.add(sep >= 0 ? matchName.substring(sep + 3).trim() : matchName);
        }
        return names;
    }

    private static void addAll(Set<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String value : source) {
            if (value != null && !value.trim().isEmpty()) {
                target.add(value.trim());
            }
        }
    }

    private Optional<Integer> lookupItem(String lowerName) {
        try {
            List<ItemPrice> results = itemManager.search(lowerName);
            if (results == null) {
                return Optional.empty();
            }
            for (ItemPrice result : results) {
                if (result.getName() != null && result.getName().equalsIgnoreCase(lowerName)) {
                    return Optional.of(result.getId());
                }
            }
        } catch (RuntimeException e) {
            log.debug("[Birdhouse] Item lookup failed for '{}': {}", lowerName, e.getMessage());
        }
        return Optional.empty();
    }

    // ===== HOST-SUPPLIED IMAGES =====

    /**
     * Most of these are wiki URLs the site filled in automatically, so the bytes usually
     * come from the wiki rather than from our own storage. Either way it happens once per
     * URL per session, and a failure is silent: a missing picture is not worth a
     * complaint in the player's chat.
     */
    private void fetchRemote(String url, JLabel target) {
        if (!remoteInFlight.add(url)) {
            return;
        }

        Request request;
        try {
            request = new Request.Builder().url(url).build();
        } catch (IllegalArgumentException e) {
            log.debug("[Birdhouse] Unusable tile image URL '{}'", url);
            remoteInFlight.remove(url);
            return;
        }

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                remoteInFlight.remove(url);
                log.debug("[Birdhouse] Tile image fetch failed: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null || body.contentLength() > MAX_REMOTE_BYTES) {
                        return;
                    }
                    ImageIcon icon = scale(readImage(body));
                    if (icon == null) {
                        return;
                    }
                    remoteIcons.put(url, icon);
                    SwingUtilities.invokeLater(() -> {
                        target.setIcon(icon);
                        target.revalidate();
                        target.repaint();
                    });
                } catch (IOException | RuntimeException e) {
                    log.debug("[Birdhouse] Tile image decode failed: {}", e.getMessage());
                } finally {
                    remoteInFlight.remove(url);
                }
            }
        });
    }

    private static BufferedImage readImage(ResponseBody body) throws IOException {
        try (InputStream in = body.byteStream()) {
            return ImageIO.read(in);
        }
    }

    private static ImageIcon scale(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return null;
        }
        double factor = Math.min(
            REMOTE_ICON_SIZE / (double) image.getWidth(),
            REMOTE_ICON_SIZE / (double) image.getHeight());
        if (factor >= 1.0) {
            return new ImageIcon(image);
        }
        int w = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(image.getHeight() * factor));
        return new ImageIcon(image.getScaledInstance(w, h, Image.SCALE_SMOOTH));
    }

    /** Dropped when the plugin stops so a long session doesn't hold images forever. */
    void clear() {
        remoteIcons.clear();
        remoteInFlight.clear();
        itemIds.clear();
    }
}
