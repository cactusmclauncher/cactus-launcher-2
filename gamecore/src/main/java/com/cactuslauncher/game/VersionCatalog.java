package com.cactuslauncher.game;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class VersionCatalog {
    public record Version(String id, String type, String releasedAt, boolean latestRelease, boolean latestSnapshot) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private List<Version> cached;
    private Instant loadedAt = Instant.EPOCH;

    public synchronized List<Version> load() throws IOException {
        if (cached != null && loadedAt.plusSeconds(900).isAfter(Instant.now())) return cached;
        var request = HttpRequest.newBuilder(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", "CactusLauncher/2.0").GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Каталог Minecraft недоступен: HTTP " + response.statusCode());
            return parseAndCache(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка каталога отменена.", e);
        }
    }

    private List<Version> parseAndCache(String json) throws IOException {
        var versions = parse(json);
        cached = versions;
        loadedAt = Instant.now();
        return versions;
    }

    public static List<Version> parse(String json) throws IOException {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            var latest = root.getAsJsonObject("latest");
            String release = latest.get("release").getAsString();
            String snapshot = latest.get("snapshot").getAsString();
            var result = new ArrayList<Version>();
            for (var entry : root.getAsJsonArray("versions")) {
                var value = entry.getAsJsonObject();
                String id = value.get("id").getAsString();
                String type = value.get("type").getAsString();
                String date = value.get("releaseTime").getAsString();
                Instant.parse(date);
                if (id.isBlank()) throw new IllegalArgumentException();
                result.add(new Version(id, type, date, id.equals(release), id.equals(snapshot)));
            }
            if (result.isEmpty()) throw new IllegalArgumentException();
            result.sort(Comparator.comparing((Version v) -> Instant.parse(v.releasedAt())).reversed());
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new IOException("Не удалось прочитать каталог версий Minecraft.", e);
        }
    }
}
