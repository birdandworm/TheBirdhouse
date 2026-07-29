package com.thebirdhouse.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for communicating with The Birdhouse backend.
 * All requests are async to avoid blocking the game thread.
 */
@Slf4j
@Singleton
public class BirdhouseApiClient {

    private static final String BASE_URL = "https://thebirdhouse.games/api/plugin";
    // Dedicated always-on cache host for the high-frequency board poll. This is a
    // read-only mirror that serves byte-identical /board responses far more cheaply
    // than Cloud Functions. Only fetchBoard() uses it; if it is ever unreachable or
    // errors, fetchBoard() transparently falls back to BASE_URL, so boards keep
    // working even if this host is down.
    private static final String BOARD_BASE_URL = "https://api.thebirdhouse.games";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType IMAGE_TYPE = MediaType.parse("image/jpeg");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private String authToken = "";

    @Inject
    public BirdhouseApiClient(OkHttpClient httpClient, Gson gson) {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    public void setAuthToken(String token) {
        this.authToken = token != null ? token : "";
    }

    /**
     * Submit a drop proof to The Birdhouse.
     */
    public CompletableFuture<Boolean> submitProof(ProofPayload payload, byte[] screenshot) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload", gson.toJson(payload));

                if (screenshot != null && screenshot.length > 0) {
                    builder.addFormDataPart("screenshot", "proof.jpg",
                        RequestBody.create(IMAGE_TYPE, screenshot));
                }

                Request request = new Request.Builder()
                    .url(BASE_URL + "/submit-proof")
                    .header("Authorization", "Bearer " + authToken)
                    .post(builder.build())
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log.info("Proof submitted successfully for tile: {}", payload.getTileName());
                        return true;
                    } else {
                        log.warn("Proof submission failed: {} {}", response.code(), response.message());
                        return false;
                    }
                }
            } catch (IOException e) {
                log.error("Failed to submit proof", e);
                return false;
            }
        });
    }

    /**
     * Fetch the active board tiles for a room so we can match drops locally.
     *
     * Tries the dedicated cache host first (cheap, always-on) and falls back to the
     * primary backend only when the cache host itself is unhealthy, so a cache-host
     * outage never breaks board loading.
     *
     * A 4xx is NOT a reason to fall back: both hosts read the same database, so an
     * unknown room code or a non-member token gets the identical answer from either
     * one. Retrying would just double every poll, forever, for anyone sitting on a
     * stale room code.
     */
    public CompletableFuture<BoardData> fetchBoard(String roomCode) {
        return CompletableFuture.supplyAsync(() -> {
            BoardResult cached = requestBoard(BOARD_BASE_URL, roomCode);
            if (cached.board != null) {
                return cached.board;
            }
            if (!cached.retryable) {
                return null;
            }
            log.debug("Board cache host unavailable for {}, falling back to primary backend", roomCode);
            return requestBoard(BASE_URL, roomCode).board;
        });
    }

    /**
     * Outcome of one board request: the parsed board when it succeeded, plus whether
     * a different host is worth trying (host unreachable / 5xx) versus the response
     * being an authoritative answer we'd only receive again (4xx).
     */
    private static final class BoardResult {
        private final BoardData board;
        private final boolean retryable;

        private BoardResult(BoardData board, boolean retryable) {
            this.board = board;
            this.retryable = retryable;
        }
    }

    /**
     * Perform a single GET /board/{roomCode} against the given base URL.
     */
    private BoardResult requestBoard(String base, String roomCode) {
        try {
            Request request = new Request.Builder()
                .url(base + "/board/" + roomCode)
                .header("Authorization", "Bearer " + authToken)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    BoardData parsed = gson.fromJson(response.body().string(), BoardData.class);
                    // A 2xx we can't parse means this host is misbehaving, not that the
                    // room is bad, so the caller may still try elsewhere.
                    return new BoardResult(parsed, parsed == null);
                }
                int code = response.code();
                return new BoardResult(null, code >= 500 || code == 429 || code == 408);
            }
        } catch (IOException e) {
            log.debug("Board fetch failed from {}: {}", base, e.getMessage());
            return new BoardResult(null, true);
        }
    }

    /**
     * Report a session event (login/logout/heartbeat).
     */
    public void reportSession(SessionEvent event) {
        CompletableFuture.runAsync(() -> {
            try {
                Request request = new Request.Builder()
                    .url(BASE_URL + "/session")
                    .header("Authorization", "Bearer " + authToken)
                    .post(RequestBody.create(JSON_TYPE, gson.toJson(event)))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.debug("Session report failed: {}", response.code());
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to report session", e);
            }
        });
    }

    /**
     * Report a batched activity delta (active time, NPC kills, total loot GP) to the
     * /loot endpoint. Fire-and-forget: never blocks the game thread and swallows
     * failures so a hiccup can't disrupt gameplay (the next flush re-sends).
     */
    public void reportActivity(ActivityPayload payload) {
        if (authToken == null || authToken.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Request request = new Request.Builder()
                    .url(BASE_URL + "/loot")
                    .header("Authorization", "Bearer " + authToken)
                    .post(RequestBody.create(JSON_TYPE, gson.toJson(payload)))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.debug("Activity report failed: {}", response.code());
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to report activity", e);
            }
        });
    }

    /**
     * Fetch the player's active rooms for auto-detection.
     */
    public CompletableFuture<java.util.List<ActiveRoom>> fetchActiveRooms() {
        return CompletableFuture.supplyAsync(() -> {
            if (authToken == null || authToken.isEmpty()) {
                log.warn("[Birdhouse] Cannot fetch active rooms — no auth token configured");
                return java.util.Collections.<ActiveRoom>emptyList();
            }
            try {
                Request request = new Request.Builder()
                    .url(BASE_URL + "/active-rooms")
                    .header("Authorization", "Bearer " + authToken)
                    .get()
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        JsonObject obj = gson.fromJson(body, JsonObject.class);
                        if (obj.has("rooms")) {
                            ActiveRoom[] rooms = gson.fromJson(obj.get("rooms"), ActiveRoom[].class);
                            log.info("[Birdhouse] Active rooms response: {} rooms found", rooms.length);
                            return java.util.Arrays.asList(rooms);
                        }
                    } else {
                        String errBody = response.body() != null ? response.body().string() : "(no body)";
                        log.warn("[Birdhouse] Active rooms request failed: {} {} - {}", response.code(), response.message(), errBody);
                    }
                }
            } catch (IOException e) {
                log.error("[Birdhouse] Failed to fetch active rooms", e);
            }
            return java.util.Collections.emptyList();
        });
    }
}
