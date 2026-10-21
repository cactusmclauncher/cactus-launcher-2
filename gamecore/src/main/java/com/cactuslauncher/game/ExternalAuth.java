package com.cactuslauncher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Yggdrasil authentication against a third-party authlib-injector server. */
public final class ExternalAuth {
    /**
     * @param serverUrl the address the user entered, normalized; used to match the launch request
     * @param apiRoot   the Yggdrasil API root after authlib-injector discovery; passed to the java agent
     */
    public record Session(String name, String uuid, String accessToken, String clientToken, String apiRoot, String serverUrl) {
        /** True if {@code url} names this session's server, either as entered or as the discovered API root. */
        public boolean matches(String url) {
            try {
                String normalized = normalizeApiRoot(url);
                return normalized.equalsIgnoreCase(serverUrl) || normalized.equalsIgnoreCase(apiRoot);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    /** The server answered and refused the token or credentials (as opposed to being unreachable). */
    public static final class RejectedException extends IOException {
        public RejectedException(String message) { super(message); }
    }

    record Response(int status, String body) {}

    /** HTTP seam so validation and refresh can be tested without a server. */
    interface Transport {
        Response post(URI uri, String json) throws IOException, InterruptedException;
        /** The X-Authlib-Injector-API-Location header of {@code uri}, resolved to an absolute URL, or "". */
        default String apiLocationHeader(URI uri) throws IOException, InterruptedException { return ""; }
    }

    private final Transport transport;

    public ExternalAuth() { this(new HttpTransport()); }

    ExternalAuth(Transport transport) { this.transport = transport; }

    public Session authenticate(String url, String username, String password) throws IOException {
        if (username.isBlank() || password.isEmpty()) throw new IllegalArgumentException("Укажите логин и пароль.");
        String clientToken = UUID.randomUUID().toString();
        String entered = normalizeApiRoot(url);
        String root = discoverApiRoot(entered);
        var json = post(root + "/authserver/authenticate", authenticateBody(username, password, clientToken));
        var profile = json.has("selectedProfile") && json.get("selectedProfile").isJsonObject() ? json.getAsJsonObject("selectedProfile") : null;
        if (profile == null && json.has("availableProfiles") && json.get("availableProfiles").isJsonArray()) {
            JsonArray profiles = json.getAsJsonArray("availableProfiles");
            if (profiles.size() == 1) profile = profiles.get(0).getAsJsonObject();
            else if (profiles.size() > 1) throw new IOException("У аккаунта несколько профилей. Сервер должен выбрать профиль при входе.");
        }
        if (profile == null) throw new IOException("У аккаунта нет выбранного профиля Minecraft.");
        String id = validProfileId(profile.get("id").getAsString());
        return new Session(profile.get("name").getAsString(), id, string(json, "accessToken"),
                json.has("clientToken") ? json.get("clientToken").getAsString() : clientToken, root, entered);
    }

    /** True if the server still accepts the access token; {@link RejectedException} never escapes from here. */
    public boolean validate(Session session) throws IOException {
        Response response = send(session.apiRoot() + "/authserver/validate", tokenBody(session, false));
        if (response.status() == 204 || response.status() == 200) return true;
        if (response.status() == 401 || response.status() == 403 || response.status() == 400) return false;
        throw new IOException("Сервер авторизации вернул ошибку " + response.status() + ".");
    }

    public Session refresh(Session session) throws IOException {
        var json = post(session.apiRoot() + "/authserver/refresh", tokenBody(session, true));
        var profile = json.has("selectedProfile") && json.get("selectedProfile").isJsonObject() ? json.getAsJsonObject("selectedProfile") : null;
        return new Session(profile != null ? profile.get("name").getAsString() : session.name(),
                profile != null ? validProfileId(profile.get("id").getAsString()) : session.uuid(),
                string(json, "accessToken"), session.clientToken(), session.apiRoot(), session.serverUrl());
    }

    /**
     * Returns a session the server accepts right now: the same one if it validates, a refreshed one otherwise.
     * If the server cannot be reached the current session is returned, so offline single-player keeps working.
     *
     * @throws RejectedException if the server refused both the token and the refresh; the user must sign in again
     */
    public Session ensureValid(Session session) throws IOException {
        try {
            if (validate(session)) return session;
        } catch (IOException unreachable) {
            return session;
        }
        try {
            return refresh(session);
        } catch (RejectedException rejected) {
            throw new RejectedException("Сессия на сервере авторизации истекла. Войдите заново в профилях.");
        } catch (IOException unreachable) {
            return session;
        }
    }

    static String authenticateBody(String username, String password, String clientToken) {
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        JsonObject body = new JsonObject();
        body.add("agent", agent);
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty("clientToken", clientToken);
        body.addProperty("requestUser", true);
        return body.toString();
    }

    static String tokenBody(Session session, boolean requestUser) {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", session.accessToken());
        body.addProperty("clientToken", session.clientToken());
        if (requestUser) body.addProperty("requestUser", true);
        return body.toString();
    }

    private String discoverApiRoot(String entered) throws IOException {
        try {
            String location = transport.apiLocationHeader(URI.create(entered)).trim();
            if (location.isEmpty()) return entered;
            return normalizeApiRoot(URI.create(entered).resolve(location).toASCIIString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Поиск API сервера отменён.", e);
        } catch (IOException e) {
            return entered;
        } catch (RuntimeException e) {
            throw new IOException("Сервер вернул некорректный адрес API.", e);
        }
    }

    static String normalizeApiRoot(String url) {
        try {
            URI uri = URI.create(url.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPath().contains("..")) {
                throw new IllegalArgumentException();
            }
            String value = uri.toASCIIString();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Укажите адрес сервера в формате https://домен или https://домен/api.", e);
        }
    }

    private static String validProfileId(String id) throws IOException {
        if (id == null || !id.matches("[a-fA-F0-9]{32}")) throw new IOException("Сервер вернул некорректный идентификатор профиля.");
        return id;
    }

    private static String string(JsonObject json, String key) throws IOException {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank())
            throw new IOException("Сервер авторизации не вернул " + key + ".");
        return value.getAsString();
    }

    private Response send(String url, String body) throws IOException {
        try {
            return transport.post(URI.create(url), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Вход отменён.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректный адрес сервера авторизации.", e);
        }
    }

    private JsonObject post(String url, String body) throws IOException {
        Response response = send(url, body);
        JsonElement json;
        try {
            json = response.body() == null || response.body().isBlank() ? null : JsonParser.parseString(response.body());
        } catch (RuntimeException e) {
            json = null;
        }
        if (response.status() / 100 != 2) {
            String message = json != null && json.isJsonObject() && json.getAsJsonObject().has("errorMessage")
                    ? json.getAsJsonObject().get("errorMessage").getAsString()
                    : "Сервер авторизации вернул ошибку " + response.status() + ".";
            if (response.status() == 401 || response.status() == 403 || response.status() == 400) throw new RejectedException(message);
            throw new IOException(message);
        }
        if (json == null || !json.isJsonObject()) throw new IOException("Некорректный ответ сервера авторизации.");
        return json.getAsJsonObject();
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        @Override
        public String apiLocationHeader(URI uri) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            String location = response.headers().firstValue("X-Authlib-Injector-API-Location").orElse("");
            // Resolve against the final URI after redirects, as before.
            return location.isBlank() ? "" : response.uri().resolve(location.trim()).toASCIIString();
        }

        @Override
        public Response post(URI uri, String json) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }
    }
}
