package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalAuthTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final ExternalAuth.Session SESSION =
            new ExternalAuth.Session("Steve", ID, "old-token", "client\"\\token", "https://auth.example/api/yggdrasil", "https://auth.example");

    /** Answers POSTs in order and records the paths that were called. */
    private static final class FakeTransport implements ExternalAuth.Transport {
        final List<Object> answers;
        final List<String> calls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        FakeTransport(Object... answers) { this.answers = new ArrayList<>(List.of(answers)); }

        @Override
        public ExternalAuth.Response post(URI uri, String json) throws IOException {
            calls.add(uri.getPath());
            bodies.add(json);
            Object next = answers.remove(0);
            if (next instanceof IOException error) throw error;
            return (ExternalAuth.Response) next;
        }
    }

    private static ExternalAuth.Response response(int status, String body) { return new ExternalAuth.Response(status, body); }

    @Test
    void authenticateBodyIsValidJsonForAnyPassword() {
        String password = "p\"a\\s\ns\u0001w😀rd";
        var json = JsonParser.parseString(ExternalAuth.authenticateBody("user\"name", password, "token")).getAsJsonObject();
        assertEquals(password, json.get("password").getAsString());
        assertEquals("user\"name", json.get("username").getAsString());
        assertEquals("Minecraft", json.getAsJsonObject("agent").get("name").getAsString());
        assertTrue(json.get("requestUser").getAsBoolean());
    }

    @Test
    void tokenBodyEscapesTokensAndOmitsRequestUserForValidate() {
        var validate = JsonParser.parseString(ExternalAuth.tokenBody(SESSION, false)).getAsJsonObject();
        assertEquals("client\"\\token", validate.get("clientToken").getAsString());
        assertFalse(validate.has("requestUser"));
        var refresh = JsonParser.parseString(ExternalAuth.tokenBody(SESSION, true)).getAsJsonObject();
        assertTrue(refresh.get("requestUser").getAsBoolean());
    }

    @Test
    void validSessionIsKeptWithoutRefresh() throws Exception {
        var transport = new FakeTransport(response(204, ""));
        assertSame(SESSION, new ExternalAuth(transport).ensureValid(SESSION));
        assertEquals(List.of("/api/yggdrasil/authserver/validate"), transport.calls);
    }

    @Test
    void expiredSessionIsRefreshed() throws Exception {
        var transport = new FakeTransport(response(403, "{\"error\":\"ForbiddenOperationException\"}"),
                response(200, "{\"accessToken\":\"new-token\",\"selectedProfile\":{\"id\":\"" + ID + "\",\"name\":\"Alex\"}}"));
        var fresh = new ExternalAuth(transport).ensureValid(SESSION);
        assertEquals("new-token", fresh.accessToken());
        assertEquals("Alex", fresh.name());
        assertEquals(SESSION.clientToken(), fresh.clientToken());
        assertEquals(List.of("/api/yggdrasil/authserver/validate", "/api/yggdrasil/authserver/refresh"), transport.calls);
    }

    @Test
    void refusedRefreshRequiresSignInAgain() {
        var transport = new FakeTransport(response(403, ""), response(403, "{\"errorMessage\":\"Invalid token.\"}"));
        assertThrows(ExternalAuth.RejectedException.class, () -> new ExternalAuth(transport).ensureValid(SESSION));
    }

    @Test
    void unreachableServerKeepsSessionForOfflinePlay() throws Exception {
        assertSame(SESSION, new ExternalAuth(new FakeTransport(new IOException("offline"))).ensureValid(SESSION));
        var refreshOffline = new FakeTransport(response(403, ""), new IOException("offline"));
        assertSame(SESSION, new ExternalAuth(refreshOffline).ensureValid(SESSION));
    }

    @Test
    void refreshRejectsMalformedProfileId() {
        var transport = new FakeTransport(response(200, "{\"accessToken\":\"t\",\"selectedProfile\":{\"id\":\"../x\",\"name\":\"A\"}}"));
        assertThrows(IOException.class, () -> new ExternalAuth(transport).refresh(SESSION));
    }

    @Test
    void sessionMatchesEnteredOrDiscoveredServer() {
        assertTrue(SESSION.matches("https://auth.example/"));
        assertTrue(SESSION.matches("https://auth.example/api/yggdrasil"));
        assertFalse(SESSION.matches("https://other.example"));
        assertFalse(SESSION.matches("http://auth.example"));
        assertFalse(SESSION.matches("not a url"));
    }
}
