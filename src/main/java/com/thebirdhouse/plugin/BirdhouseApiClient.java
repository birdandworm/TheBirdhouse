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
     * Tries the dedicated cache host first (cheap, always-on) and transparently
     * falls back to the primary backend on any network error or non-2xx response,
     * so a cache-host outage never breaks board loading.
     */
    public CompletableFuture<BoardData> fetchBoard(String roomCode) {
        return CompletableFuture.supplyAsync(() -> {
            BoardData board = requestBoard(BOARD_BASE_URL, roomCode);
            if (board != null) {
                return board;
            }
            log.debug("Board cache host unavailable for {}, falling back to primary backend", roomCode);
            return requestBoard(BASE_URL, roomCode);
        });
    }

    /**
     * Perform a single GET /board/{roomCode} against the given base URL.
     * Returns parsed BoardData on a 2xx response with a body, or null on any
     * network error / non-2xx so the caller can fall back to another host.
     */
    private BoardData requestBoard(String base, String roomCode) {
        try {
            Request request = new Request.Builder()
                .url(base + "/board/" + roomCode)
                .header("Authorization", "Bearer " + authToken)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return gson.fromJson(response.body().string(), BoardData.class);
                }
            }
        } catch (IOException e) {
            log.debug("Board fetch failed from {}: {}", base, e.getMessage());
        }
        return null;
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
