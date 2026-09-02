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
     * Whether a usable token is configured. The server trims the bearer value before
     * looking it up, so a whitespace-only token is rejected there exactly as an empty
     * one is; treating both as absent here keeps us from firing a request that cannot
     * succeed. A fresh install has no token until the player pastes one, and the
     * timer-driven reporters would otherwise call on regardless.
     */
    private boolean hasAuthToken() {
        return authToken != null && !authToken.trim().isEmpty();
    }

    /**
     * Submit a drop proof to The Birdhouse.
     */
    public CompletableFuture<ProofResult> submitProof(ProofPayload payload, byte[] screenshot) {
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
                    String body = response.body() != null ? response.body().string() : "";
                    ProofResult result = interpretProofResponse(response.code(), body);
                    if (result.isOk()) {
                        log.info("Proof submitted successfully for tile: {}", payload.getTileName());
                    } else {
                        log.warn("Proof submission failed for tile {}: {} ({})",
                            payload.getTileName(), result.getMessage(), response.code());
                    }
                    return result;
                }
            } catch (IOException e) {
                log.error("Failed to submit proof", e);
                return ProofResult.rejected("Couldn't reach The Birdhouse");
            }
        });
    }

    /**
     * A refused proof still comes back as HTTP 200 with {@code success:false} and a
     * reason, because the request itself was well-formed — the drop just didn't qualify.
     * Treating any 2xx as acceptance would tell a player their manual submission landed
     * when the server actually threw it away.
     */
    private ProofResult interpretProofResponse(int code, String body) {
        JsonObject json = null;
        try {
            if (body != null && !body.isEmpty()) {
                json = gson.fromJson(body, JsonObject.class);
            }
        } catch (RuntimeException e) {
            log.debug("Unparseable submit-proof body: {}", e.getMessage());
        }

        String reason = null;
        if (json != null) {
            if (json.has("reason")) {
                reason = json.get("reason").getAsString();
            } else if (json.has("error")) {
                reason = json.get("error").getAsString();
            } else if (json.has("message")) {
                reason = json.get("message").getAsString();
            }
        }

        if (code < 200 || code >= 300) {
            return ProofResult.rejected(reason != null ? reason : "Server returned " + code);
        }
        if (json != null && json.has("duplicate") && json.get("duplicate").getAsBoolean()) {
            return ProofResult.duplicate(reason != null ? reason : "Already submitted");
        }
        if (json != null && json.has("success") && !json.get("success").getAsBoolean()) {
            return ProofResult.rejected(reason != null ? reason : "The server rejected this proof");
        }
        return ProofResult.accepted();
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
     * Fetch the caller's own team chat thread.
     *
     * Cache host first, same as the board and for the same reason: the cache serves
     * this from a live listener, so polling it costs no database reads and a short
     * interval is affordable. A 4xx is again not a reason to fall back, since both
     * hosts read the same room roster and would answer identically.
     */
    public CompletableFuture<ChatData> fetchChat(String roomCode) {
        return CompletableFuture.supplyAsync(() -> {
            ChatResult cached = requestChat(BOARD_BASE_URL, roomCode);
            if (cached.chat != null) {
                return cached.chat;
            }
            if (!cached.retryable) {
                return null;
            }
            log.debug("Chat cache host unavailable for {}, falling back to primary backend", roomCode);
            return requestChat(BASE_URL, roomCode).chat;
        });
    }

    /** Outcome of one chat request; see BoardResult for why retryable is tracked. */
    private static final class ChatResult {
        private final ChatData chat;
        private final boolean retryable;

        private ChatResult(ChatData chat, boolean retryable) {
            this.chat = chat;
            this.retryable = retryable;
        }
    }

    private ChatResult requestChat(String base, String roomCode) {
        try {
            Request request = new Request.Builder()
                .url(base + "/chat/" + roomCode)
                .header("Authorization", "Bearer " + authToken)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    ChatData parsed = gson.fromJson(body, ChatData.class);
                    return new ChatResult(parsed, parsed == null);
                }
                int code = response.code();
                if (code >= 500 || code == 429 || code == 408) {
                    return new ChatResult(null, true);
                }
                // A 404 that did not come from our own application is a proxy declining to
                // route, not "no such room", and those two are otherwise indistinguishable
                // from here. This is how chat failed on release: the reverse proxy in front
                // of the cache host allowed /board and /healthz only, and answered /chat
                // with a plain-text 404 that looked authoritative enough to stop the
                // fallback. Trying the primary backend once costs a request; treating it as
                // final disables the feature outright.
                if (code == 404 && !isApiError(body)) {
                    return new ChatResult(null, true);
                }
                return new ChatResult(null, false);
            }
        } catch (IOException e) {
            log.debug("Chat fetch failed from {}: {}", base, e.getMessage());
            return new ChatResult(null, true);
        }
    }

    /** Whether a body is one of our own JSON errors rather than a proxy's error page. */
    private boolean isApiError(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        try {
            JsonObject json = gson.fromJson(body, JsonObject.class);
            return json != null && json.has("error");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Send one message to the caller's own team thread.
     *
     * Always the primary backend: the cache host is read-only by design, which is what
     * keeps it unable to affect game state.
     *
     * Resolves to null on success, or a short message suitable for showing the player.
     * The server's own wording is preferred where it gave any, since it is the side
     * that knows whether this was too long, too fast, or the wrong room.
     */
    public CompletableFuture<String> sendChat(String roomCode, String text) {
        return CompletableFuture.supplyAsync(() -> {
            if (!hasAuthToken()) {
                return "No auth token";
            }
            try {
                JsonObject body = new JsonObject();
                body.addProperty("text", text);

                Request request = new Request.Builder()
                    .url(BASE_URL + "/chat/" + roomCode)
                    .header("Authorization", "Bearer " + authToken)
                    .post(RequestBody.create(JSON_TYPE, gson.toJson(body)))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        return null;
                    }
                    String reason = null;
                    if (response.body() != null) {
                        try {
                            JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                            if (json != null && json.has("error")) {
                                reason = json.get("error").getAsString();
                            }
                        } catch (RuntimeException e) {
                            log.debug("Unparseable chat error body: {}", e.getMessage());
                        }
                    }
                    return reason != null ? reason : "Server returned " + response.code();
                }
            } catch (IOException e) {
                log.debug("Chat send failed: {}", e.getMessage());
                return "Could not reach the server";
            }
        });
    }

    /**
     * Report a session event (login/logout/heartbeat).
     */
    public void reportSession(SessionEvent event) {
        if (!hasAuthToken()) {
            return;
        }
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
        if (!hasAuthToken()) {
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
     * Report an impling caught into a jar (Clue Trail).
     *
     * Sent as it happens rather than batched with the activity flush: the jar is usually
     * opened within seconds, and the clue it yields is only credited if the catch is
     * already on record.
     */
    public void reportImplingCatch(String roomCode, String impling, int count) {
        if (!hasAuthToken() || impling == null || impling.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                if (roomCode != null && !roomCode.isEmpty()) {
                    body.addProperty("roomCode", roomCode);
                }
                body.addProperty("impling", impling);
                body.addProperty("count", Math.max(1, count));

                Request request = new Request.Builder()
                    .url(BASE_URL + "/impling-catch")
                    .header("Authorization", "Bearer " + authToken)
                    .post(RequestBody.create(JSON_TYPE, gson.toJson(body)))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.debug("Impling catch report failed: {}", response.code());
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to report impling catch", e);
            }
        });
    }

    /**
     * Report a loot drop, clue completion, or collection-log entry to the clan
     * leaderboard. Fire-and-forget: failures are silently swallowed so they can
     * never disrupt gameplay or the bingo submission path.
     */
    public void reportClanLoot(JsonObject payload) {
        CompletableFuture.runAsync(() -> {
            try {
                Request.Builder rb = new Request.Builder()
                    .url(BASE_URL + "/clan-loot")
                    .post(RequestBody.create(JSON_TYPE, gson.toJson(payload)));
                if (hasAuthToken()) {
                    rb.header("Authorization", "Bearer " + authToken);
                }
                Request request = rb.build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.debug("Clan loot report failed: {}", response.code());
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to report clan loot", e);
            }
        });
    }

    /**
     * Fetch the player's active rooms for auto-detection.
     */
    public CompletableFuture<java.util.List<ActiveRoom>> fetchActiveRooms() {
        return CompletableFuture.supplyAsync(() -> {
            if (!hasAuthToken()) {
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
