package com.thebirdhouse.plugin;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Reading the backend's answer to a submitted proof.
 *
 * A kill-count tile keeps one proof per player and counts up rather than filing a proof
 * per kill, so only the first kill's screenshot is ever stored. The running count in the
 * response is how the client learns a tally is open and stops capturing for that tile —
 * on something cannoned down several to the tick, that is the difference between a few
 * frames a second being encoded and uploaded and none at all.
 *
 * The count must never be mistaken for anything else, and its absence must never be
 * mistaken for a failure: plenty of servers and every other kind of proof omit it.
 */
public class ProofResponseTest {

    private final BirdhouseApiClient client =
        new BirdhouseApiClient(new OkHttpClient(), new Gson());

    @Test
    public void readsTheRunningKillCount() {
        ProofResult r = client.interpretProofResponse(200, "{\"ok\":true,\"kills\":3472}");
        assertTrue(r.isOk());
        assertEquals(3472, r.getKills());
    }

    @Test
    public void theFirstKillOfATallyReportsOne() {
        ProofResult r = client.interpretProofResponse(200, "{\"ok\":true,\"kills\":1}");
        assertTrue(r.isOk());
        assertEquals(1, r.getKills());
    }

    @Test
    public void anOrdinaryDropCarriesNoCount() {
        ProofResult r = client.interpretProofResponse(200, "{\"ok\":true,\"proofId\":\"abc\"}");
        assertTrue(r.isOk());
        assertEquals("a tile with no tally keeps screenshotting", 0, r.getKills());
    }

    @Test
    public void anOlderServerThatSaysNothingIsStillAccepted() {
        ProofResult r = client.interpretProofResponse(200, "{}");
        assertTrue(r.isOk());
        assertEquals(0, r.getKills());
    }

    @Test
    public void anEmptyBodyIsStillAccepted() {
        ProofResult r = client.interpretProofResponse(200, "");
        assertTrue(r.isOk());
        assertEquals(0, r.getKills());
    }

    @Test
    public void aNonsenseCountDoesNotThrowAwayTheProof() {
        // Losing the submission over a field that only saves screenshots would be a
        // bad trade.
        ProofResult r = client.interpretProofResponse(200, "{\"ok\":true,\"kills\":\"lots\"}");
        assertTrue(r.isOk());
        assertEquals(0, r.getKills());
    }

    @Test
    public void aDuplicateCarriesNoCount() {
        // A collapsed twin changed nothing, so it must not be read as opening a tally.
        ProofResult r = client.interpretProofResponse(200, "{\"ok\":true,\"duplicate\":true}");
        assertTrue(r.isOk());
        assertTrue(r.isDuplicate());
        assertEquals(0, r.getKills());
    }

    @Test
    public void aRefusedProofIsStillRefused() {
        ProofResult r = client.interpretProofResponse(
            200, "{\"success\":false,\"reason\":\"That region is still locked\"}");
        assertFalse(r.isOk());
        assertEquals("That region is still locked", r.getMessage());
        assertEquals(0, r.getKills());
    }

    @Test
    public void anErrorStatusIsRejectedWhateverTheBodySays() {
        ProofResult r = client.interpretProofResponse(500, "{\"ok\":true,\"kills\":10}");
        assertFalse(r.isOk());
        assertEquals(0, r.getKills());
    }
}
