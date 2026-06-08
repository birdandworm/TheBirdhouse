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
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType IMAGE = MediaType.parse("image/jpeg");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private String authToken = "";

    @Inject
    public BirdhouseApiClient() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .writeTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofSeconds(15))
            .build();
        this.gson = new Gson();
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
                        RequestBody.create(IMAGE, screenshot));
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
     */
    public CompletableFuture<BoardData> fetchBoard(String roomCode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Request request = new Request.Builder()
                    .url(BASE_URL + "/board/" + roomCode)
                    .header("Authorization", "Bearer " + authToken)
                    .get()
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        return gson.fromJson(response.body().string(), BoardData.class);
                    }
                }
            } catch (IOException e) {
                log.error("Failed to fetch board data", e);
            }
            return null;
        });
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
                    .post(RequestBody.create(JSON, gson.toJson(event)))
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
                            return java.util.Arrays.asList(rooms);
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to fetch active rooms", e);
            }
            return java.util.Collections.emptyList();
        });
    }
}
