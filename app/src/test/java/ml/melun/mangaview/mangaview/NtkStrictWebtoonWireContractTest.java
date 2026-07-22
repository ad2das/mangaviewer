package ml.melun.mangaview.mangaview;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NtkStrictWebtoonWireContractTest {
    @Test
    public void webtoonPayloadHasOnlyTheFiveDeployedFields() throws Exception {
        JSONObject payload = CustomHttpClient.strictWebtoonViewerImagesPayloadForTest(
                "726211",
                "143500",
                "token-value",
                "nonce-value",
                "proof-value"
        );

        Set<String> actual = new HashSet<>();
        payload.keys().forEachRemaining(actual::add);
        assertEquals(new HashSet<>(Arrays.asList(
                "workId", "episodeId", "token", "nonce", "proof")), actual);
        assertEquals("726211", payload.getString("workId"));
        assertEquals("143500", payload.getString("episodeId"));
        assertEquals("token-value", payload.getString("token"));
        assertEquals("nonce-value", payload.getString("nonce"));
        assertEquals("proof-value", payload.getString("proof"));
        assertFalse(payload.has("path"));
        assertFalse(payload.has("requestKeyId"));
    }

    @Test
    public void webtoonProofMessageIsTokenDotNonceWithoutUserAgent() {
        assertEquals(
                "token-value.nonce-value",
                CustomHttpClient.strictWebtoonProofMessageForTest(
                        "token-value", "nonce-value")
        );
    }

    @Test
    public void onlyManhwaUsesNtkRequestSigningOnTheWire() {
        assertFalse(CustomHttpClient.strictImageEndpointUsesNtkWireSignatureForTest(
                "/api/webtoon-images"));
        assertTrue(CustomHttpClient.strictImageEndpointUsesNtkWireSignatureForTest(
                "/api/manhwa-images"));
    }
}
