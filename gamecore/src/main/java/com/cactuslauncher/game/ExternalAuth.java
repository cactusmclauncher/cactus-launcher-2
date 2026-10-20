package com.cactuslauncher.game;

import com.google.gson.JsonArray;
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
    public record Session(String name, String uuid, String accessToken, String clientToken, String apiRoot) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public Session authenticate(String url, String username, String password) throws IOException {
        if (username.isBlank() || password.isEmpty()) throw new IllegalArgumentException("Укажите логин и пароль.");
        String clientToken = UUID.randomUUID().toString();
        String root = discoverApiRoot(url);
        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        JsonObject requestBody = new JsonObject();
        requestBody.add("agent", agent);
        requestBody.addProperty("username", username);
        requestBody.addProperty("password", password);
        requestBody.addProperty("clientToken", clientToken);
        requestBody.addProperty("requestUser", true);
        var json = post(endpoint(root, "/authserver/authenticate"), requestBody.toString());
        var profile = json.getAsJsonObject("selectedProfile");
        if (profile == null && json.has("availableProfiles") && json.get("availableProfiles").isJsonArray()) {
            JsonArray profiles = json.getAsJsonArray("availableProfiles");
            if (profiles.size() == 1) profile = profiles.get(0).getAsJsonObject();
            else if (profiles.size() > 1) throw new IOException("У аккаунта несколько профилей. Сервер должен выбрать профиль при входе.");
        }
        if (profile == null) throw new IOException("У аккаунта нет выбранного профиля Minecraft.");
        String name = profile.get("name").getAsString();
        String id = profile.get("id").getAsString();
        if (!id.matches("[a-fA-F0-9]{32}")) throw new IOException("Сервер вернул некорректный идентификатор профиля.");
        return new Session(name, id, json.get("accessToken").getAsString(),
                json.has("clientToken") ? json.get("clientToken").getAsString() : clientToken, root);
    }

    public Session refresh(Session session) throws IOException {
        String body = "{\"accessToken\":\"" + escape(session.accessToken()) + "\",\"clientToken\":\"" + escape(session.clientToken()) + "\",\"requestUser\":true}";
        var json = post(session.apiRoot() + "/authserver/refresh", body);
        var profile = json.getAsJsonObject("selectedProfile");
        return new Session(profile != null ? profile.get("name").getAsString() : session.name(),
                profile != null ? profile.get("id").getAsString() : session.uuid(),
                json.get("accessToken").getAsString(), session.clientToken(), session.apiRoot());
    }

    private String discoverApiRoot(String url) throws IOException {
        String entered = normalizeApiRoot(url);
        try {
            URI uri = URI.create(entered);
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            String location = response.headers().firstValue("X-Authlib-Injector-API-Location").orElse("").trim();
            if (location.isEmpty()) return entered;
            URI resolved = response.uri().resolve(location);
            return normalizeApiRoot(resolved.toASCIIString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Поиск API сервера отменён.", e);
        } catch (IOException e) {
            return entered;
        } catch (RuntimeException e) {
            throw new IOException("Сервер вернул некорректный адрес API.", e);
        }
    }

    private static String normalizeApiRoot(String url) {
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

    private static String endpoint(String root, String path) {
        return root + path;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private com.google.gson.JsonObject post(String url, String body) throws IOException {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var json = JsonParser.parseString(response.body());
            if (response.statusCode() / 100 != 2) {
                String message = json.isJsonObject() && json.getAsJsonObject().has("errorMessage")
                        ? json.getAsJsonObject().get("errorMessage").getAsString()
                        : "Сервер авторизации вернул ошибку " + response.statusCode() + ".";
                throw new IOException(message);
            }
            return json.getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Вход отменён.", e);
        } catch (RuntimeException e) {
            throw new IOException("Некорректный ответ сервера авторизации.", e);
        }
    }

}
